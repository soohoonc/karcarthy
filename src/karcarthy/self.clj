(ns karcarthy.self
  "Metacircular karcarthy: agents reading, writing and editing karcarthy itself.

  Workflows and agents are EDN data, not code. So an agent can emit karcarthy
  EDN as text, and karcarthy can parse it with `clojure.edn` (data only, never
  evaluating code), validate it, and run it. That is how agents use the
  language themselves:

    * `dsl-reference`: a prompt fragment teaching an agent the EDN DSL.
    * `read-workflow` / `read-agent`: parse an agent's output into validated
      karcarthy data (no code eval).
    * `evolve` (a `:evolve` node): an agent edits its own definition
      (instructions, model, tools) at runtime by emitting an EDN patch, then
      retrying with the new behavior.

  Requiring this namespace registers the `:evolve` node with the interpreter."
  (:require [clojure.string :as str]
            [karcarthy.core :as k]
            [karcarthy.edn :as kedn]
            [karcarthy.orchestrate :as o]))

;; ---------------------------------------------------------------------------
;; Safe parsing of agent output into karcarthy data
;; ---------------------------------------------------------------------------

(defn extract-edn
  "Pull the first EDN map out of `s`, which may contain prose or a ```edn fence.
  Uses `clojure.edn/read-string` - data only, never evaluates code. Throws
  ex-info if no map is found or it doesn't parse."
  [s]
  (kedn/extract-map s))

(defn read-workflow
  "Parse `s` (an agent's output) into a runnable karcarthy workflow, validating
  the whole tree. Throws ex-info if it isn't a valid workflow."
  [s]
  (let [v (extract-edn s)]
    (when-not (o/workflow? v)
      (throw (ex-info "parsed value is not a runnable karcarthy workflow" {:parsed v})))
    (doseq [node (tree-seq coll? seq v)]
      (when (and (map? node) (= :agent (:karcarthy/type node)) (not (k/agent? node)))
        (throw (ex-info "generated workflow contains an invalid agent" {:agent node}))))
    v))

(defn read-agent
  "Parse `s` into a valid karcarthy agent, or throw ex-info."
  [s]
  (let [v (extract-edn s)]
    (if (k/agent? v)
      v
      (throw (ex-info "parsed value is not a valid agent" {:parsed v})))))

;; ---------------------------------------------------------------------------
;; Teaching the language to agents
;; ---------------------------------------------------------------------------

(def dsl-reference
  "A prompt fragment describing the karcarthy EDN DSL so an agent can generate
  workflows."
  (str/join
   "\n"
   ["karcarthy workflows are plain EDN data. A workflow is either an AGENT or a NODE."
    ""
    "AGENT (a leaf that does one unit of work):"
    "  {:karcarthy/type :agent"
    "   :name \"researcher\""
    "   :instructions \"Research the question and cite sources.\""
    "   :model \"sonnet\"}            ; :model optional"
    ""
    "NODES (compose workflows):"
    "  pipe    {:karcarthy/type :pipe :steps [WORKFLOW ...]}"
    "          run in sequence, feeding each result's text into the next."
    "  map     {:karcarthy/type :map :branches [WORKFLOW ...]}"
    "          run each workflow on the same input."
    "  map     {:karcarthy/type :map :planner AGENT :worker WORKFLOW}"
    "          planner lists subtasks (one per line); worker handles each."
    "  reduce  add :reduce to a :map node."
    "  iterate {:karcarthy/type :iterate :worker WORKFLOW :evaluator AGENT"
    "           :max-rounds 3}             ; draft, critique, repeat"
    "  bind    {:karcarthy/type :bind :source AGENT"
    "           :routes {\"label\" WORKFLOW ...}}  ; prior reply picks a branch"
    "  bind    {:karcarthy/type :bind :source WORKFLOW :to WORKFLOW}"
    ""
    "Rules: output EDN only (optionally inside an ```edn fence), no prose. Use"
    "only the keys shown. Nest freely - any WORKFLOW position may hold an agent"
    "or a node."]))

;; ---------------------------------------------------------------------------
;; Agents editing their own behavior at runtime
;; ---------------------------------------------------------------------------

(defn evolve
  "A workflow node: run `agent`, letting it edit its own definition at runtime. Each
  round the agent may reply with an EDN patch
  `{:karcarthy/patch {<fields to merge>} :reason \"...\"}` to change itself (its
  :instructions, :model or :tools) and retry, or with a plain final answer.
  Stops at the final answer or `:max-rounds` (default 5)."
  [agent & {:keys [max-rounds] :or {max-rounds 5}}]
  {:karcarthy/type :evolve :agent agent :max-rounds max-rounds})

(defn- parse-patch
  "Return the :karcarthy/patch map from `text`, or nil if it isn't a patch."
  [text]
  (try
    (let [v (extract-edn text)]
      (when (and (map? v) (contains? v :karcarthy/patch))
        (:karcarthy/patch v)))
    (catch Exception _ nil)))

(defn- evolve-prompt [input]
  (str "You may improve yourself before answering. Reply with EITHER:\n"
       "  an EDN patch to your own definition -\n"
       "  {:karcarthy/patch {:instructions \"<better instructions>\"} :reason \"<why>\"}\n"
       "  (you may patch :instructions, :model and/or :tools) to change and retry,\n"
       "OR your final answer as plain text (no EDN).\n\nTASK:\n" input))

(defmethod o/run-node :evolve
  [adapter {:keys [agent max-rounds] :or {max-rounds 5}} input opts]
  (loop [round 1, agent agent, patches []]
    (let [r     (k/run-agent adapter agent (evolve-prompt input) opts)
          patch (parse-patch (:text r))]
      (cond
        ;; agent wants to change itself and has rounds left -> apply + retry
        (and patch (< round max-rounds))
        (recur (inc round) (merge agent patch) (conj patches patch))

        ;; out of rounds but still patching -> apply once and force a final answer
        patch
        (let [final-agent (merge agent patch)
              fr          (k/run-agent adapter final-agent input opts)]
          (k/result (assoc fr :agent (:name final-agent) :rounds round
                           :patches (conj patches patch) :evolved final-agent)))

        ;; a plain final answer
        :else
        (k/result (assoc r :agent (:name agent) :rounds round
                         :patches patches :evolved agent))))))

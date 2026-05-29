(ns karcarthy.self
  "Metacircular karcarthy: agents reading, writing and editing karcarthy itself.

  Workflows and agents are EDN data, not code. So an agent can emit karcarthy
  EDN as text, and karcarthy can parse it with `clojure.edn` (data only, never
  evaluating code), validate it, and run it. That is how agents use the
  language themselves:

    * `dsl-reference`: a prompt fragment teaching an agent the EDN DSL.
    * `read-workflow` / `read-agent`: parse an agent's output into validated
      karcarthy data (no code eval).
    * `run-authored`: have an agent write a workflow for a task, then run it.
    * `evolve` (a `:evolve` node): an agent edits its own definition
      (instructions, model, tools) at runtime by emitting an EDN patch, then
      retrying with the new behavior.

  Requiring this namespace registers the `:evolve` node with the interpreter."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [karcarthy.core :as k]
            [karcarthy.orchestrate :as o]))

;; ---------------------------------------------------------------------------
;; Safe parsing of agent output into karcarthy data
;; ---------------------------------------------------------------------------

(defn extract-edn
  "Pull the first EDN map out of `s`, which may contain prose or a ```edn fence.
  Uses `clojure.edn/read-string` - data only, never evaluates code. Throws
  ex-info if no map is found or it doesn't parse."
  [s]
  (let [s      (str s)
        fenced (re-find #"(?s)```(?:edn|clojure|clj)?\s*(.*?)```" s)
        body   (if fenced (second fenced) s)
        idx    (str/index-of body "{")]
    (when (nil? idx)
      (throw (ex-info "no EDN map found in output" {:input s})))
    (try
      (edn/read-string (subs body idx))
      (catch Exception e
        (throw (ex-info (str "could not parse EDN: " (.getMessage e))
                        {:input s} e))))))

(defn read-workflow
  "Parse `s` (an agent's output) into a runnable karcarthy workflow, validating
  the whole tree. Throws ex-info if it isn't a valid workflow."
  [s]
  (let [v (extract-edn s)]
    (when-not (o/workflow? v)
      (throw (ex-info "parsed value is not a runnable karcarthy workflow" {:parsed v})))
    (doseq [node (tree-seq coll? seq v)]
      (when (and (map? node) (= :agent (:karcarthy/type node)) (not (k/agent? node)))
        (throw (ex-info "authored workflow contains an invalid agent" {:agent node}))))
    v))

(defn read-flow
  "Deprecated alias for `read-workflow`."
  [s]
  (read-workflow s))

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
  "A prompt fragment describing the karcarthy EDN DSL so an agent can author
  workflows. Append it to an authoring agent's prompt."
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
    "NODES (compose workflows; each is tagged with :karcarthy/type):"
    "  :chain       {:karcarthy/type :chain :steps [WORKFLOW ...]}"
    "               run in sequence, feeding each result's text into the next."
    "  :parallel    {:karcarthy/type :parallel :branches [WORKFLOW ...]}"
    "               run on the same input concurrently, gather all results."
    "  :route       {:karcarthy/type :route :router AGENT"
    "                :routes {\"label\" WORKFLOW ...}}  ; router's reply picks a branch"
    "  :refine      {:karcarthy/type :refine :worker WORKFLOW :evaluator AGENT"
    "                :max-rounds 3}             ; draft, critique, repeat"
    "  :orchestrate {:karcarthy/type :orchestrate :planner AGENT :worker WORKFLOW}"
    "               planner lists subtasks (one per line); worker handles each."
    "  :handoff     {:karcarthy/type :handoff :from WORKFLOW :to WORKFLOW}"
    ""
    "Rules: output EDN only (optionally inside an ```edn fence), no prose. Use"
    "only the keys shown. Nest freely - any WORKFLOW position may hold an agent"
    "or a node."]))

(defn authoring-prompt
  "The prompt given to an author agent: the DSL reference plus the task."
  [task]
  (str dsl-reference
       "\n\n---\nWrite ONE karcarthy workflow as EDN that best accomplishes this"
       " task. Output EDN only.\n\nTASK:\n" task))

(defn run-authored
  "Have `author` write a karcarthy workflow (EDN) for `task`, then run it.
  Returns {:author <author result> :workflow <parsed workflow> :result <run result>}.
  The deprecated `:flow` key is also returned for compatibility.
  `:input` overrides what the authored workflow runs on (defaults to `task`)."
  [runner author task & {:keys [input opts] :or {opts {}}}]
  (let [ar   (k/run-agent runner author (authoring-prompt task) opts)
        workflow (read-workflow (:text ar))]
    {:author ar
     :workflow workflow
     :flow   workflow
     :result (o/run runner workflow (or input task) opts)}))

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
  [runner {:keys [agent max-rounds] :or {max-rounds 5}} input opts]
  (loop [round 1, agent agent, patches []]
    (let [r     (k/run-agent runner agent (evolve-prompt input) opts)
          patch (parse-patch (:text r))]
      (cond
        ;; agent wants to change itself and has rounds left -> apply + retry
        (and patch (< round max-rounds))
        (recur (inc round) (merge agent patch) (conj patches patch))

        ;; out of rounds but still patching -> apply once and force a final answer
        patch
        (let [final-agent (merge agent patch)
              fr          (k/run-agent runner final-agent input opts)]
          (k/result (assoc fr :agent (:name final-agent) :rounds round
                           :patches (conj patches patch) :evolved final-agent)))

        ;; a plain final answer
        :else
        (k/result (assoc r :agent (:name agent) :rounds round
                         :patches patches :evolved agent))))))

;; ---------------------------------------------------------------------------
;; A runtime-editable registry of named agents
;;
;; `evolve` lets an agent edit *itself*; a registry lets a running workflow edit a
;; *named, shared* agent so that later steps (referenced via `agent-ref`) pick up
;; the new behavior at runtime.
;; ---------------------------------------------------------------------------

(defn registry
  "A mutable store of named agents (an atom keyed by :name), built from `agents`."
  [agents]
  (atom (into {} (map (juxt :name identity)) agents)))

(defn patch-agent!
  "Merge `patch` into the registered agent `name`, returning the updated agent.
  How a running workflow edits a shared agent's behavior at runtime."
  [reg name patch]
  (-> (swap! reg update name merge patch) (get name)))

(defn put-agent!
  "Register (or replace) `agent` in `reg`, keyed by its :name."
  [reg agent]
  (swap! reg assoc (:name agent) agent)
  agent)

(defn agent-ref
  "A workflow node that resolves agent `name` from `reg` *at run time*, so edits
  made with `patch-agent!`/`put-agent!` take effect on subsequent runs."
  [reg name]
  {:karcarthy/type :agent-ref :registry reg :name name})

(defmethod o/run-node :agent-ref
  [runner {:keys [registry name]} input opts]
  (if-let [a (get @registry name)]
    (k/run-agent runner a input opts)
    (k/result {:ok? false :error :unknown-agent
               :text (str "no agent named " (pr-str name) " in registry")})))

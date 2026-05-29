(ns karcarthy.self
  "Metacircular karcarthy: agents reading, writing and editing karcarthy itself.

  Because flows and agents are EDN *data* (not code), an agent can emit karcarthy
  EDN as text, and karcarthy can parse it — with `clojure.edn`, **data only,
  never evaluating code** — validate it, and run it. That is how agents \"use the
  language themselves\":

    * `dsl-reference`  — a prompt fragment teaching an agent the EDN DSL.
    * `read-flow` / `read-agent` — safely parse an agent's output into karcarthy
      data (validated; no code eval).
    * `run-authored`   — have an agent *write* a flow for a task, then run it.
    * `evolve` (a `:evolve` node) — an agent *edits its own definition*
      (instructions, model, tools) at runtime by emitting an EDN patch, then
      retries with the new behavior.

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
  Uses `clojure.edn/read-string` — data only, never evaluates code. Throws
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

(defn read-flow
  "Parse `s` (an agent's output) into a runnable karcarthy flow, validating the
  whole tree. Throws ex-info if it isn't a valid flow."
  [s]
  (let [v (extract-edn s)]
    (when-not (o/flow? v)
      (throw (ex-info "parsed value is not a runnable karcarthy flow" {:parsed v})))
    (doseq [node (tree-seq coll? seq v)]
      (when (and (map? node) (= :agent (:karcarthy/type node)) (not (k/agent? node)))
        (throw (ex-info "authored flow contains an invalid agent" {:agent node}))))
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
  "A prompt fragment describing the karcarthy EDN DSL so an agent can author
  flows. Append it to an authoring agent's prompt."
  (str/join
   "\n"
   ["karcarthy flows are plain EDN data. A flow is either an AGENT or a NODE."
    ""
    "AGENT (a leaf that does one unit of work):"
    "  {:karcarthy/type :agent"
    "   :name \"researcher\""
    "   :instructions \"Research the question and cite sources.\""
    "   :model \"sonnet\"}            ; :model optional"
    ""
    "NODES (compose flows; each is tagged with :karcarthy/type):"
    "  :chain       {:karcarthy/type :chain :steps [FLOW ...]}"
    "               run in sequence, feeding each result's text into the next."
    "  :parallel    {:karcarthy/type :parallel :branches [FLOW ...]}"
    "               run on the same input concurrently, gather all results."
    "  :route       {:karcarthy/type :route :router AGENT"
    "                :routes {\"label\" FLOW ...}}  ; router's reply picks a branch"
    "  :refine      {:karcarthy/type :refine :worker FLOW :evaluator AGENT"
    "                :max-rounds 3}             ; draft, critique, repeat"
    "  :orchestrate {:karcarthy/type :orchestrate :planner AGENT :worker FLOW}"
    "               planner lists subtasks (one per line); worker handles each."
    "  :handoff     {:karcarthy/type :handoff :from FLOW :to FLOW}"
    ""
    "Rules: output EDN only (optionally inside an ```edn fence), no prose. Use"
    "only the keys shown. Nest freely — any FLOW position may hold an agent or a"
    "node."]))

(defn authoring-prompt
  "The prompt given to an author agent: the DSL reference plus the task."
  [task]
  (str dsl-reference
       "\n\n---\nWrite ONE karcarthy flow as EDN that best accomplishes this"
       " task. Output EDN only.\n\nTASK:\n" task))

(defn run-authored
  "Have `author` write a karcarthy flow (EDN) for `task`, then run that flow.
  Returns {:author <author result> :flow <parsed flow> :result <run result>}.
  `:input` overrides what the authored flow runs on (defaults to `task`)."
  [harness author task & {:keys [input opts] :or {opts {}}}]
  (let [ar   (k/run-agent harness author (authoring-prompt task) opts)
        flow (read-flow (:text ar))]
    {:author ar
     :flow   flow
     :result (o/run-flow harness flow (or input task) opts)}))

;; ---------------------------------------------------------------------------
;; Agents editing their own behavior at runtime
;; ---------------------------------------------------------------------------

(defn evolve
  "A flow node: run `agent`, letting it edit its own definition at runtime. Each
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
       "  an EDN patch to your own definition —\n"
       "  {:karcarthy/patch {:instructions \"<better instructions>\"} :reason \"<why>\"}\n"
       "  (you may patch :instructions, :model and/or :tools) to change and retry,\n"
       "OR your final answer as plain text (no EDN).\n\nTASK:\n" input))

(defmethod o/run-node :evolve
  [harness {:keys [agent max-rounds] :or {max-rounds 5}} input opts]
  (loop [round 1, agent agent, patches []]
    (let [r     (k/run-agent harness agent (evolve-prompt input) opts)
          patch (parse-patch (:text r))]
      (cond
        ;; agent wants to change itself and has rounds left -> apply + retry
        (and patch (< round max-rounds))
        (recur (inc round) (merge agent patch) (conj patches patch))

        ;; out of rounds but still patching -> apply once and force a final answer
        patch
        (let [final-agent (merge agent patch)
              fr          (k/run-agent harness final-agent input opts)]
          (k/result (assoc fr :agent (:name final-agent) :rounds round
                           :patches (conj patches patch) :evolved final-agent)))

        ;; a plain final answer
        :else
        (k/result (assoc r :agent (:name agent) :rounds round
                         :patches patches :evolved agent))))))

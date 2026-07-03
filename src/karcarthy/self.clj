(ns karcarthy.self
  "Metacircular karcarthy: agents reading, writing and editing karcarthy itself.

  Workflows and agents are EDN data, not code. So an agent can emit karcarthy
  EDN as text, and karcarthy can parse it with `clojure.edn` (data only, never
  evaluating code), validate it, and run it. That is how agents use the
  language themselves:

    * `dsl-reference`: a prompt fragment teaching an agent the EDN DSL.
    * `read-workflow` / `read-agent`: parse an agent's output into validated
      karcarthy data (no code eval).
    * `evolve` (a `:evolve` node): an agent edits its own definition at runtime
      by emitting an EDN patch, then retrying with the new behavior.

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
  (kedn/extract-map! s))

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
    "   :description \"Use for research tasks.\" ; optional"
    "   :instructions \"Research the question and cite sources.\""
    "   :model \"sonnet\"             ; optional runner config"
    "   :tools [\"WebSearch\"]        ; optional runner config"
    "   :config {}}                  ; optional runner-specific config"
    ""
    "NODES (compose workflows):"
    "  pipe     {:karcarthy/type :pipe :steps [WORKFLOW ...]}"
    "           run in sequence, feeding each result's text into the next."
    "  branch   {:karcarthy/type :branch :branches [WORKFLOW ...]}"
    "           run each workflow on the same input."
    "  delegate {:karcarthy/type :delegate :planner AGENT :worker WORKFLOW}"
    "           planner replies {:subtasks [\"...\"]}; worker handles each."
    "  reduce   {:karcarthy/type :reduce :source BRANCH-OR-DELEGATE :reducer WORKFLOW}"
    "           reducer receives {:input ... :subtasks ... :results [...]}"
    "  revise   {:karcarthy/type :revise :worker WORKFLOW :evaluator AGENT"
    "           :max-rounds 3} ; evaluator replies {:accept? bool :feedback \"...\"}"
    "  route    {:karcarthy/type :route :source AGENT"
    "           :routes {:label WORKFLOW ...}} ; source replies {:route :label}"
    "  continue {:karcarthy/type :continue :source WORKFLOW :to WORKFLOW}"
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
  :description, :instructions, :model, :tools or :config) and retry,
  or with a plain final answer.
  Stops at the final answer or `:max-rounds` (default 5)."
  [agent & {:keys [max-rounds] :or {max-rounds 5} :as opts}]
  (k/reject-unknown! "evolve" [:max-rounds] opts)
  {:karcarthy/type :evolve :agent agent :max-rounds max-rounds})

(defmethod o/node? :evolve
  [{:keys [agent max-rounds]}]
  (and (k/agent? agent)
       (or (nil? max-rounds)
           (and (integer? max-rounds) (pos? max-rounds)))))

(def ^:private no-patch ::no-patch)

(def ^:private patch-checks
  "Patchable agent fields: key -> [valid? description]. The single source of
  truth for both the allowed key set and the per-key validation."
  {:description  [string? "a string"]
   :instructions [string? "a string"]
   :model        [(some-fn nil? string?) "a string or nil"]
   :tools        [#(and (vector? %) (every? string? %)) "a vector of strings"]
   :config       [map? "a map"]})

(defn- validate-patch!
  "Throw unless `patch` is a well-formed :karcarthy/patch for `agent`;
  returns `patch`."
  [agent patch]
  (when-not (map? patch)
    (throw (ex-info ":karcarthy/patch must be a map" {:patch patch})))
  (when-let [unknown (seq (remove (set (keys patch-checks)) (keys patch)))]
    (throw (ex-info ":karcarthy/patch contains unknown keys"
                    {:unknown (vec unknown)
                     :supported (vec (keys patch-checks))})))
  (doseq [[k [valid? expected]] patch-checks]
    (when (and (contains? patch k) (not (valid? (get patch k))))
      (throw (ex-info (str ":karcarthy/patch " k " must be " expected)
                      {:patch patch}))))
  (let [agent' (merge agent patch)]
    (when-not (k/agent? agent')
      (throw (ex-info ":karcarthy/patch would produce an invalid agent"
                      {:patch patch
                       :agent agent'
                       :problems (k/explain-agent agent')}))))
  patch)

(defn- text->patch
  "Return a validated :karcarthy/patch map, or `no-patch` for a plain answer."
  [agent text]
  (try
    (let [v (extract-edn text)]
      (if (and (map? v) (contains? v :karcarthy/patch))
        (validate-patch! agent (:karcarthy/patch v))
        no-patch))
    (catch Exception e
      (if (= "no EDN map found in output" (.getMessage e))
        no-patch
        (throw e)))))

(defn- evolve-prompt [input]
  (str "You may improve yourself before answering. Reply with EITHER:\n"
       "  an EDN patch to your own definition -\n"
       "  {:karcarthy/patch {:instructions \"<better instructions>\"} :reason \"<why>\"}\n"
       "  (you may patch :description, :instructions, :model, :tools and/or :config) to change and retry,\n"
       "OR your final answer as plain text (no EDN).\n\nTASK:\n" input))

(defmethod o/run-node :evolve
  [runner {:keys [agent max-rounds] :or {max-rounds 5}} input opts]
  (loop [round 1, agent agent, patches []]
    (let [r (k/run-agent runner agent (evolve-prompt input) opts)]
      (if-not (k/ok? r)
        r
        (let [p (try
                  (text->patch agent (:text r))
                  (catch Throwable t
                    {::error t}))]
          (cond
            (::error p)
            (k/result {:ok? false
                       :error :invalid-patch
                       :text (ex-message (::error p))
                       :agent (:name agent)
                       :rounds round
                       :patches patches
                       :evolved agent})

            ;; agent wants to change itself and has rounds left -> apply + retry
            (and (not= no-patch p) (< round max-rounds))
            (recur (inc round) (merge agent p) (conj patches p))

            ;; out of rounds but still patching -> apply once and force a final answer
            (not= no-patch p)
            (let [final-agent (merge agent p)
                  fr          (k/run-agent runner final-agent input opts)]
              (k/result (assoc fr :agent (:name final-agent) :rounds round
                               :patches (conj patches p) :evolved final-agent)))

            ;; a plain final answer
            :else
            (k/result (assoc r :agent (:name agent) :rounds round
                             :patches patches :evolved agent))))))))

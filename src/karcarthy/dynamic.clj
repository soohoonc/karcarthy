(ns karcarthy.dynamic
  "Dynamic workflows: an agent defines, patches, calls, and spawns agents and
  workflows *during* a run.

  A `dynamic` node runs an agent in an op loop: each step the agent emits one
  EDN op - define, patch, remove, call, spawn, or complete - against mutable
  run state (`state`), until it emits `{:op :complete ...}`. Workflows stored
  in state may contain late-bound `agent-ref` / `workflow-ref` leaves, which
  `refs->workflow` resolves against the state at call time, so a stored
  workflow picks up later patches to the agents it names.

  This is an extension namespace in the same mold as `karcarthy.self`:
  requiring it registers the `:dynamic` node with the `karcarthy.orchestrate`
  interpreter via the `run-node` and `node?` multimethods.

  Experimental: the whole namespace is tagged `^:experimental` — the dynamic
  op protocol and prompt format may change between releases."
  (:require [clojure.string :as str]
            [karcarthy.core :as k]
            [karcarthy.edn :as kedn]
            [karcarthy.orchestrate :as o]))

(defn- name-key [x]
  (cond
    (keyword? x) (name x)
    (string? x) x
    :else (str x)))

;; ---------------------------------------------------------------------------
;; Constructors
;; ---------------------------------------------------------------------------

(defn ^:experimental agent-ref
  "A late-bound reference to an agent in a dynamic workflow run (experimental)."
  [name]
  {:karcarthy/type :agent-ref :name (name-key name)})

(defn ^:experimental workflow-ref
  "A late-bound reference to a named workflow in a dynamic workflow run
  (experimental)."
  [name]
  {:karcarthy/type :workflow-ref :name (name-key name)})

(defn ^:experimental dynamic
  "Run `agent` in an op loop: each step it emits one EDN op to define, patch,
  remove, call, or spawn agents and workflows (see `dynamic-reference`), until
  it emits `{:op :complete ...}`. Options:
    :max-steps  fail the run after this many ops (default 25).

  Experimental: the dynamic op protocol and prompt format may change between
  releases."
  [agent & {:keys [max-steps] :or {max-steps 25} :as opts}]
  (k/reject-unknown! "dynamic" [:max-steps] opts)
  {:karcarthy/type :dynamic
   :agent      agent
   :max-steps  max-steps})

;; ---------------------------------------------------------------------------
;; Run state
;; ---------------------------------------------------------------------------

(defn- dynamic-workflow?
  "Like `karcarthy.orchestrate/workflow?`, but allows `agent-ref` and
  `workflow-ref` leaves."
  [x]
  (cond
    (o/workflow? x) true
    (not (map? x)) false
    :else
    (case (:karcarthy/type x)
      :agent-ref    (contains? x :name)
      :workflow-ref (contains? x :name)
      :pipe         (and (sequential? (:steps x))
                         (every? dynamic-workflow? (:steps x)))
      :branch       (and (sequential? (:branches x))
                         (every? dynamic-workflow? (:branches x)))
      :delegate     (and (dynamic-workflow? (:planner x))
                         (dynamic-workflow? (:worker x)))
      :reduce       (and (dynamic-workflow? (:source x))
                         (dynamic-workflow? (:reducer x)))
      :revise       (and (dynamic-workflow? (:worker x))
                         (dynamic-workflow? (:evaluator x)))
      :route        (and (dynamic-workflow? (:source x))
                         (map? (:routes x))
                         (every? dynamic-workflow? (vals (:routes x)))
                         (or (not (contains? x :default))
                             (dynamic-workflow? (:default x))))
      :continue     (and (dynamic-workflow? (:source x))
                         (dynamic-workflow? (:to x)))
      :evolve       (dynamic-workflow? (:agent x))
      :dynamic      (o/node? x)
      false)))

(defn- validate-agent [agent]
  (if (k/agent? agent)
    agent
    (throw (ex-info "invalid agent"
                    {:agent agent
                     :explain (k/explain-agent agent)}))))

(defn- validate-dynamic-workflow [workflow]
  (if (dynamic-workflow? workflow)
    workflow
    (throw (ex-info "invalid workflow" {:workflow workflow}))))

(defn ^:experimental state
  "Create mutable state for one dynamic workflow run."
  [& {:keys [agents workflows history]}]
  (doseq [agent agents] (validate-agent agent))
  (doseq [[_ workflow] workflows] (validate-dynamic-workflow workflow))
  (atom {:agents    (into {} (for [agent agents]
                               [(name-key (:name agent)) agent]))
         :workflows (into {} (for [[name workflow] workflows]
                               [(name-key name) workflow]))
         :history   (vec history)}))

(defn ^:experimental snapshot
  "Return dynamic workflow run state as plain data."
  [state]
  @state)

(defn- lookup-agent [state name]
  (let [n (name-key name)]
    (or (get-in @state [:agents n])
        (throw (ex-info (str "unknown agent: " (pr-str n))
                        {:name n :known (vec (keys (:agents @state)))})))))

(defn- lookup-workflow [state name]
  (let [n (name-key name)]
    (or (get-in @state [:workflows n])
        (throw (ex-info (str "unknown workflow: " (pr-str n))
                        {:name n :known (vec (keys (:workflows @state)))})))))

(defn ^:experimental refs->workflow
  "Resolve `agent-ref` and `workflow-ref` values against dynamic run state."
  ([state workflow] (refs->workflow state workflow #{}))
  ([state workflow seen]
   (let [rec #(refs->workflow state % seen)]
     (cond
       (k/agent? workflow) workflow
       (not (map? workflow)) workflow
       :else
       (case (:karcarthy/type workflow)
         :agent-ref
         (lookup-agent state (:name workflow))

         :workflow-ref
         (let [n      (name-key (:name workflow))
               marker [:workflow n]]
           (when (contains? seen marker)
             (throw (ex-info "cyclic workflow reference"
                             {:name n :seen seen})))
           (refs->workflow state (lookup-workflow state n) (conj seen marker)))

         :pipe     (update workflow :steps #(mapv rec %))
         :branch   (update workflow :branches #(mapv rec %))
         :delegate (-> workflow (update :planner rec) (update :worker rec))
         :reduce   (-> workflow (update :source rec) (update :reducer rec))
         :revise   (-> workflow (update :worker rec) (update :evaluator rec))
         :continue (-> workflow (update :source rec) (update :to rec))
         :evolve   (update workflow :agent rec)

         :route
         (cond-> (-> workflow
                     (update :source rec)
                     (update :routes update-vals rec))
           (contains? workflow :default) (update :default rec))

         workflow)))))

;; ---------------------------------------------------------------------------
;; Ops
;; ---------------------------------------------------------------------------

(def ^:private op-kinds
  #{:define :patch :remove :call :spawn :complete})

(defn- op-kind [op]
  (let [k (or (:op op) (:karcarthy/op op))]
    (cond
      (keyword? k) k
      (string? k) (keyword k)
      :else k)))

(defn- normalize-op [op]
  (let [k (op-kind op)]
    (when-not (contains? op-kinds k)
      (throw (ex-info "unknown dynamic workflow op"
                      {:op op :known (vec op-kinds)})))
    (assoc op :op k)))

(defn ^:experimental text->op
  "Parse the first EDN map in `text` into a dynamic workflow op."
  [text]
  (normalize-op (kedn/extract-map! text)))

(defn- compact-result [result]
  (select-keys result [:karcarthy/type :ok? :agent :text :error :rounds
                       :subtasks :results :kind :name :removed? :value]))

(defn- remember! [state op result]
  (swap! state update :history conj {:op     (select-keys op [:op :agent :workflow :name])
                                     :result (compact-result result)})
  result)

(defn- op-input [op]
  (or (:input op) (:prompt op) ""))

(defn- op-result
  "Result map for a state-changing dynamic op, e.g. \"defined agent writer\"."
  [verb kind n & {:as extra}]
  (k/result (merge {:agent "dynamic" :kind kind :name n
                    :text  (str verb " " (name kind) " " n)}
                   extra)))

(defn- op-target-name [op target-key]
  (let [target (get op target-key)]
    (cond
      (or (string? target) (keyword? target)) target
      (map? target) (or (:name target) (:id target))
      :else (or (:name op) (:id op) (:target op)))))

(defn- op-agent [op]
  (let [agent (:agent op)]
    (if (k/agent? agent)
      agent
      (let [source (if (map? agent) agent op)
            n      (or (:name source)
                       (:name op)
                       (when (or (string? agent) (keyword? agent)) agent))]
        (validate-agent
         (cond-> {:karcarthy/type :agent
                  :name           (name-key n)
                  :instructions   (:instructions source)}
           (:description source) (assoc :description (:description source))
           (:model source)       (assoc :model (:model source))
           (:tools source)       (assoc :tools (vec (:tools source)))
           (:config source)      (assoc :config (:config source))))))))

(defn- define! [state op]
  (cond
    (contains? op :agent)
    (let [agent (op-agent op)
          n     (name-key (:name agent))]
      (swap! state assoc-in [:agents n] agent)
      (op-result "defined" :agent n))

    (contains? op :workflow)
    (let [n        (name-key (:name op))
          workflow (validate-dynamic-workflow (:workflow op))]
      (when (str/blank? n)
        (throw (ex-info "define workflow requires :name" {:op op})))
      (swap! state assoc-in [:workflows n] workflow)
      (op-result "defined" :workflow n))

    :else
    (throw (ex-info "define requires :agent or :workflow" {:op op}))))

(defn- patch! [state op]
  (let [patch (or (:merge op) (:patch op))]
    (when-not (map? patch)
      (throw (ex-info "patch requires :merge map" {:op op})))
    (cond
      (contains? op :agent)
      (let [n       (name-key (op-target-name op :agent))
            current (lookup-agent state n)
            updated (validate-agent (merge current patch))]
        (swap! state assoc-in [:agents n] updated)
        (op-result "patched" :agent n))

      (contains? op :workflow)
      (let [n       (name-key (op-target-name op :workflow))
            current (lookup-workflow state n)
            updated (validate-dynamic-workflow (merge current patch))]
        (swap! state assoc-in [:workflows n] updated)
        (op-result "patched" :workflow n))

      :else
      (throw (ex-info "patch requires :agent or :workflow target" {:op op})))))

(defn- remove! [state op]
  (cond
    (contains? op :agent)
    (let [n (name-key (op-target-name op :agent))]
      (swap! state update :agents dissoc n)
      (op-result "removed" :agent n :removed? true))

    (contains? op :workflow)
    (let [n (name-key (op-target-name op :workflow))]
      (swap! state update :workflows dissoc n)
      (op-result "removed" :workflow n :removed? true))

    :else
    (throw (ex-info "remove requires :agent or :workflow target" {:op op}))))

(defn- call-once [runner state op input opts]
  (cond
    (contains? op :agent)
    (let [target (:agent op)
          agent  (if (k/agent? target)
                   target
                   (lookup-agent state (op-target-name op :agent)))]
      (o/safe-run runner agent (o/input->text input) opts))

    (contains? op :workflow)
    (let [target   (:workflow op)
          workflow (if (or (string? target) (keyword? target))
                     (lookup-workflow state target)
                     target)]
      (o/safe-run runner
                  (refs->workflow state workflow)
                  (o/input->text input)
                  opts))

    :else
    (throw (ex-info "call requires :agent or :workflow target" {:op op}))))

(defn- call! [runner state op opts]
  (call-once runner state op (op-input op) opts))

(defn- op-workflow [state op]
  (let [target (:workflow op)]
    (if (or (string? target) (keyword? target))
      (lookup-workflow state target)
      target)))

(defn- op-callable [state op]
  (cond
    (contains? op :agent)
    (let [target (:agent op)]
      (if (k/agent? target)
        target
        (lookup-agent state (op-target-name op :agent))))

    (contains? op :workflow)
    (refs->workflow state (op-workflow state op))

    :else
    (throw (ex-info "spawn requires :agent or :workflow target" {:op op}))))

(defn- spawn! [runner state op opts]
  (let [inputs (:inputs op)]
    (when-not (and (sequential? inputs) (seq inputs))
      (throw (ex-info "spawn requires one or more :inputs" {:op op})))
    (let [workflow (op-callable state op)
          indexed  (map-indexed vector inputs)
          results  (o/bounded-pmap (:max-concurrency op)
                                   (fn [[idx input]]
                                     (o/safe-run runner workflow (o/input->text input)
                                                 (o/descend opts :spawn idx)))
                                   indexed
                                   opts)]
      (k/result {:agent   "dynamic"
                 :ok?     (every? k/ok? results)
                 :results results
                 :text    (str/join "\n\n" (keep :text results))}))))

(defn ^:experimental step!
  "Apply one dynamic workflow op to state."
  ([runner state op] (step! runner state op {}))
  ([runner state op opts]
   (let [op     (normalize-op op)
         result (case (:op op)
                  :define   (define! state op)
                  :patch    (patch! state op)
                  :remove   (remove! state op)
                  :call     (call! runner state op opts)
                  :spawn    (spawn! runner state op opts)
                  :complete (k/result {:agent "dynamic"
                                       :text  (str (or (:text op)
                                                       (:content op)
                                                       (:value op)
                                                       ""))
                                       :value (:value op)}))]
     (remember! state op result))))

;; ---------------------------------------------------------------------------
;; The :dynamic node
;; ---------------------------------------------------------------------------

(def ^:experimental dynamic-reference
  "Prompt fragment teaching a dynamic workflow agent the op protocol: how the
  loop works, every op with its meaning, the workflow grammar, EDN rules, and
  a worked example."
  (str/join
   "\n"
   ["You are the orchestrator of one karcarthy dynamic workflow run."
    ""
    "HOW THIS WORKS"
    "karcarthy runs agents and workflows described as plain EDN data. You hold a"
    "registry of named agents and named workflows. Each step you see the task,"
    "the registry (AGENTS / WORKFLOWS below), the run HISTORY, and your last"
    "op's result; you reply with EXACTLY ONE EDN op map. The host executes it"
    "and shows you the result next step. End the run with :complete."
    ""
    "OPS (agent and workflow names are strings)"
    "{:op :define :agent {:name \"writer\" :instructions \"...\" :model \"sonnet\"}}"
    "  register an agent; :name and :instructions required, :description /"
    "  :model / :tools [\"...\"] optional."
    "{:op :define :name \"draft\" :workflow WORKFLOW}"
    "  register a workflow built from the grammar below."
    "{:op :patch :agent \"writer\" :merge {:instructions \"...\"}}"
    "{:op :patch :workflow \"draft\" :merge {...}}"
    "  merge fields into a registered agent or workflow."
    "{:op :remove :agent \"writer\"}   {:op :remove :workflow \"draft\"}"
    "  delete from the registry."
    "{:op :call :agent \"writer\" :input \"...\"}"
    "{:op :call :workflow \"draft\" :input \"...\"}"
    "  run the target once on :input; its result is your next LAST RESULT."
    "{:op :spawn :agent \"reviewer\" :inputs [\"a\" \"b\"]}"
    "{:op :spawn :workflow \"review\" :inputs [\"a\" \"b\"]}"
    "  run the target once per input, concurrently; results come back in order."
    "{:op :complete :text \"final answer\"}"
    "  finish the run; :text is the final answer to the task. Completion is"
    "  rejected while a failed :call or :spawn remains unresolved."
    ""
    "WORKFLOW grammar (any WORKFLOW position takes an agent map, a ref, or a node)"
    "{:karcarthy/type :agent-ref :name \"writer\"}       an agent from the registry"
    "{:karcarthy/type :workflow-ref :name \"draft\"}     a workflow from the registry"
    "  (refs resolve at call time, so later patches take effect)"
    "{:karcarthy/type :pipe :steps [WORKFLOW ...]}      run in sequence, text flows"
    "{:karcarthy/type :branch :branches [WORKFLOW ...]} same input to each"
    "{:karcarthy/type :delegate :planner WORKFLOW :worker WORKFLOW}"
    "  planner replies {:subtasks [\"...\"]}; worker runs once per subtask"
    "{:karcarthy/type :reduce :source BRANCH-OR-DELEGATE :reducer WORKFLOW}"
    "{:karcarthy/type :revise :worker WORKFLOW :evaluator WORKFLOW :max-rounds 3}"
    "{:karcarthy/type :route :source WORKFLOW :routes {:label WORKFLOW}}"
    "{:karcarthy/type :continue :source WORKFLOW :to WORKFLOW}"
    ""
    "RULES"
    "- Reply with EDN, not JSON: keywords like :op, double-quoted strings,"
    "  unquoted true/false, no commas needed."
    "- Exactly one op map per reply. No prose around it (an ```edn fence is ok)."
    "- Refer to registered agents and workflows by their string :name."
    "- If LAST RESULT shows :ok? false, read its :error and send a corrected op."
    "- After a failed :call or :spawn, retry work successfully before :complete."
    ""
    "EXAMPLE (one run, one op per step)"
    "step 1 -> {:op :define :agent {:name \"researcher\""
    "                               :instructions \"Answer with one short fact.\"}}"
    "step 2 -> {:op :spawn :agent \"researcher\" :inputs [\"solar\" \"wind\"]}"
    "step 3 -> {:op :complete :text \"solar: ...  wind: ...\"}"]))

(def ^:private history-window
  "How many of the most recent history entries the dynamic prompt shows."
  10)

(defn- render-lines
  "One `render` line per item of `coll`, or a placeholder when it is empty."
  [render coll]
  (if (empty? coll)
    "(none yet)"
    (str/join "\n" (mapv render coll))))

(defn- render-agents [agents]
  (render-lines (fn [[_ agent]]
                  (pr-str (select-keys agent [:karcarthy/type :name :description
                                              :instructions :model :tools])))
                agents))

(defn- render-workflows [workflows]
  (render-lines (fn [[n workflow]] (str (pr-str n) " " (pr-str workflow)))
                workflows))

(defn- render-history [history]
  (let [recent (take-last history-window history)
        elided (- (count history) (count recent))]
    (str (when (pos? elided)
           (str "(" elided " earlier steps elided)\n"))
         (render-lines pr-str recent))))

(defn- dynamic-prompt [task state last-result step]
  (let [{:keys [agents workflows history]} @state]
    (str dynamic-reference
         "\n\nTASK:\n" task
         "\n\nSTEP: " step
         "\n\nAGENTS (registered; target by :name):\n" (render-agents agents)
         "\n\nWORKFLOWS (registered; target by name):\n" (render-workflows workflows)
         "\n\nHISTORY (op and result, oldest first):\n" (render-history history)
         "\n\nLAST RESULT EDN:\n" (pr-str (compact-result last-result))
         "\n\nOutput exactly one EDN op now.")))

(defmethod o/node? :dynamic
  [{:keys [agent max-steps]}]
  (and (k/agent? agent)
       (or (nil? max-steps)
           (and (integer? max-steps) (pos? max-steps)))))

(defmethod o/run-node :dynamic
  [runner {:keys [agent max-steps]} input opts]
  (let [st (or (:state opts) (state))]
    (loop [step 1, last-result nil, failures 0, work-failed? false]
      (if (and max-steps (> step max-steps))
        (k/result {:agent (:name agent)
                   :ok?   false
                   :error :max-steps
                   :text  (str "dynamic workflow exceeded max steps: " max-steps)
                   :state (snapshot st)})
        (let [agent-result (o/run-node runner agent
                                       (dynamic-prompt input st last-result step)
                                       opts)]
          (if-not (k/ok? agent-result)
            (k/result (assoc agent-result :state (snapshot st)))
            (let [outcome (try
                            (let [op     (text->op (:text agent-result))
                                  _      (when (and work-failed?
                                                    (= :complete (:op op)))
                                           (throw (ex-info
                                                   "cannot complete while failed work is unresolved"
                                                   {:op op})))
                                  result (step! runner st op opts)]
                              {:op op :result (assoc result
                                                     :state (snapshot st)
                                                     :steps step)})
                            (catch Throwable t
                              {:error t}))]
              (if-let [t (:error outcome)]
                ;; An invalid op is fed back as the last result - like a tool
                ;; error in an agent loop - so the agent can correct itself;
                ;; consecutive failures beyond :edn-retries abort the run.
                (let [failures (inc failures)
                      message  (or (ex-message t) (str t))]
                  (if (> failures (long (get opts :edn-retries 1)))
                    (k/result {:agent (:name agent)
                               :ok?   false
                               :error message
                               :text  (:text agent-result)
                               :state (snapshot st)
                               :raw   {:agent agent-result}})
                    (recur (inc step)
                           (k/result {:agent (:name agent)
                                      :ok?   false
                                      :error message
                                      :text  (:text agent-result)})
                           failures
                           work-failed?)))
                (let [{:keys [op result]} outcome]
                  (if (= :complete (:op op))
                    result
                    (recur (inc step)
                           result
                           0
                           (case (:op op)
                             (:call :spawn) (not (k/ok? result))
                             work-failed?))))))))))))

(ns karcarthy.orchestrate
  "Orchestration as data.

  A *workflow* is a plain Clojure value describing how to coordinate agents. It is
  either an agent (a leaf; see `karcarthy.core/agent`) or a composite built with
  functional constructors:

    pipe      run workflows in sequence, threading each result's :text into the next.
    branch    run a collection of workflows on the same input.
    delegate  ask a planner for subtasks, then run a worker over them.
    reduce    run branch/delegate work, then pass its EDN result summary to a reducer workflow.
    revise    draft, critique, and retry until accepted or a round limit is reached.
    route     choose the next workflow from a previous result.
    continue  send the previous result to the next workflow, preserving session ids.
    step      run a host Clojure function on the flowing input.
    dynamic   let an agent define, patch, call, and spawn work during a run.

  Because a workflow is data, you build, generate and serialize it with ordinary
  Clojure. Pure workflow nodes are EDN/JSON portable; `step` is deliberately
  host-local because it contains a Clojure function. Model-facing replies are
  EDN maps:

    planner   {:subtasks [\"...\"]}
    evaluator {:accept? true} or {:accept? false :feedback \"...\"}
    router    {:route :some-route}

  `run` interprets a workflow through a runner.

  Every workflow run returns a `karcarthy.core` result map, so workflows compose: the
  output of one is valid input to another. Composite nodes are fault-isolated,
  so a child that throws becomes a not-ok result instead of crashing the run
  (see `safe-run`)."
  (:refer-clojure :exclude [iterate map reduce])
  (:require [clojure.string :as str]
            [karcarthy.core :as k]
            [karcarthy.edn :as kedn])
  (:import [java.util UUID]
           [java.util.concurrent Executors Callable Future]))

;; ---------------------------------------------------------------------------
;; Constructors - build workflow data
;; ---------------------------------------------------------------------------

(defn- name-key [x]
  (cond
    (keyword? x) (name x)
    (string? x) x
    :else (str x)))

(defn pipe
  "Run `steps` in sequence, threading each result's :text into the next step."
  [& steps]
  {:karcarthy/type :pipe :steps (vec steps)})

(defn step
  "Run a host Clojure function in a workflow.

  By default, `f` receives the flowing input string. With `:context? true`, `f`
  receives `{:input input :opts opts :node node}`. A step may return text, a
  partial result map, or a full result map. Step nodes are Clojure-local and are
  not EDN/JSON portable."
  [f & {:keys [name context? context] :as opts}]
  (when-let [unknown (seq (remove #{:name :context? :context} (keys opts)))]
    (throw (ex-info "step contains unknown options"
                    {:unknown (vec unknown)
                     :supported [:name :context?]})))
  (cond-> {:karcarthy/type :step :f f}
    name (assoc :name (name-key name))
    (or context? context) (assoc :context? true)))

(defn branch
  "Run each workflow in `branches` on the same input. Options:
    :max-concurrency  max branch calls running at once."
  [branches & {:keys [max-concurrency] :as opts}]
  (when-let [unknown (seq (remove #{:max-concurrency} (keys opts)))]
    (throw (ex-info "branch contains unknown options"
                    {:unknown (vec unknown)
                     :supported [:max-concurrency]})))
  (cond-> {:karcarthy/type :branch :branches (vec branches)}
    max-concurrency (assoc :max-concurrency max-concurrency)))

(defn delegate
  "Ask `planner` for EDN `{:subtasks [\"...\"]}`, then run `worker` once per
  subtask. Options:
    :max-concurrency  max worker calls running at once."
  [planner worker & {:keys [max-concurrency] :as opts}]
  (when-let [unknown (seq (remove #{:max-concurrency} (keys opts)))]
    (throw (ex-info "delegate contains unknown options"
                    {:unknown (vec unknown)
                     :supported [:max-concurrency]})))
  (cond-> {:karcarthy/type :delegate
           :planner        planner
           :worker         worker}
    max-concurrency (assoc :max-concurrency max-concurrency)))

(defn reduce
  "Run branch/delegate work, then pass an EDN result summary to `reducer`.

  `source` is branch/delegate workflow data, or a collection of workflows which
  is first turned into `(branch workflows)`. `reducer` is a workflow. It receives
  a prompt whose body is EDN:

    {:input \"original input\"
     :subtasks [...]
     :results [{:agent \"...\" :ok? true :text \"...\"} ...]}"
  [source reducer & {:keys [max-concurrency]}]
  (let [node (if (sequential? source) (branch source) source)]
    (case (:karcarthy/type node)
      (:branch :delegate)
      {:karcarthy/type :reduce
       :source         (cond-> node
                         max-concurrency (assoc :max-concurrency max-concurrency))
       :reducer        reducer}

      (throw (ex-info "reduce expects branch/delegate workflow data"
                      {:workflow source :type (:karcarthy/type node)})))))

(defn revise
  "Run a worker/evaluator loop until accepted or `:max-rounds` is reached.

  The evaluator must reply with EDN:
    {:accept? true}
    {:accept? false :feedback \"what to improve\"}"
  [worker evaluator & {:keys [max-rounds] :or {max-rounds 3}}]
  {:karcarthy/type :revise :worker worker :evaluator evaluator :max-rounds max-rounds})

(defn route
  "Run `source`, read EDN `{:route key}`, then run the matching workflow from
  `routes` on the original input. Matching is exact. Options:
    :default  workflow to run when the label is not in `routes`."
  [source routes & {:keys [default] :as opts}]
  (if (contains? opts :default)
    {:karcarthy/type :route :source source :routes routes :default default}
    {:karcarthy/type :route :source source :routes routes}))

(defn continue
  "Run `source`, then send its result text to `to`, preserving session ids when
  the runner supports them. Options:
    :prompt  override the prompt sent to `to`."
  [source to & {:keys [prompt] :as opts}]
  (if (contains? opts :prompt)
    {:karcarthy/type :continue :source source :to to :prompt prompt}
    {:karcarthy/type :continue :source source :to to}))

(defn agent-ref
  "A late-bound reference to an agent in a dynamic workflow run."
  [name]
  {:karcarthy/type :agent-ref :name (name-key name)})

(defn workflow-ref
  "A late-bound reference to a named workflow in a dynamic workflow run."
  [name]
  {:karcarthy/type :workflow-ref :name (name-key name)})

(defn dynamic
  "Build a dynamic workflow. The agent emits EDN ops during the run."
  [agent & {:keys [max-steps] :or {max-steps 25}}]
  {:karcarthy/type :dynamic
   :agent      agent
   :max-steps  max-steps})

;; ---------------------------------------------------------------------------
;; Interpreter
;; ---------------------------------------------------------------------------

(declare run)

(defmulti run-node
  "Execute one workflow node, dispatching on (:karcarthy/type node). This is the
  interpreter's extension point: teach it a new node by adding a constructor and
  a `(defmethod run-node :your-type [runner node input opts] ...)` returning a
  `karcarthy.core` result. See `karcarthy.self` for examples (`:evolve`)."
  (fn [_runner node _input _opts] (:karcarthy/type node)))

(defmulti node?
  "Validate a non-core workflow node for `workflow?`.

  Extension namespaces that add `run-node` methods should also add a `node?`
  method for the same `:karcarthy/type`; otherwise `workflow?` will reject the
  node."
  (fn [node] (:karcarthy/type node)))

(defmethod node? :default [_] false)

;; --- shared helpers --------------------------------------------------------

(defn- span-id [] (str (UUID/randomUUID)))

(defn- now-ms [] (System/currentTimeMillis))

(defn- duration-ms [started-ns]
  (/ (double (- (System/nanoTime) started-ns)) 1000000.0))

(defn- observe!
  [opts event]
  (when-let [observe (:observe opts)]
    (try
      (observe event)
      (catch Throwable _ nil))))

(defn- descend
  [opts & path-segments]
  (if (:observe opts)
    (update opts :karcarthy/path (fnil into []) path-segments)
    opts))

(defn- event
  [phase span-id parent-span-id workflow attrs]
  (let [type      (:karcarthy/type workflow)
        type-name (if type (name type) "unknown")]
    (cond-> {:karcarthy/type :event
             :event          phase
             :kind           :workflow
             :name           (str "karcarthy.workflow." type-name)
             :span/kind      :internal
             :span/id        span-id
             :time-ms        (now-ms)
             :path           (:karcarthy/path attrs)
             :attributes     {"karcarthy.kind"          "workflow"
                              "karcarthy.workflow.type" type-name}}
      parent-span-id (assoc :parent/span-id parent-span-id)
      (:karcarthy/path attrs)
      (assoc-in [:attributes "karcarthy.path"] (pr-str (:karcarthy/path attrs)))
      (:duration-ms attrs) (assoc :duration-ms (:duration-ms attrs))
      (contains? attrs :ok?) (assoc :ok? (:ok? attrs))
      (:error attrs) (assoc :error (:error attrs)))))

(defn safe-run
  "Run a child workflow, converting a thrown exception into a not-ok result so one
  bad branch can't crash a whole multi-agent run. Composite nodes run their
  children through this; top-level `run` stays transparent (fail fast)."
  [runner workflow input opts]
  (try
    (run runner workflow input opts)
    (catch Throwable t
      (k/result {:ok?       false
                 :text      nil
                 :error     (or (.getMessage t) (str t))
                 :exception (.getName (class t))}))))

(defn- dispatch
  "Map `f` over `coll`, running at most `n` (default 16) calls concurrently via
  a fixed thread pool. Returns results in order. `f` should not throw (wrap it
  in `safe-run`)."
  [n f coll]
  (let [n    (max 1 (or n 16))
        pool (Executors/newFixedThreadPool n)]
    (try
      (->> coll
           (mapv (fn [x] (.submit pool ^Callable (fn [] (f x)))))
           (mapv (fn [^Future fut] (.get fut))))
      (finally (.shutdown pool)))))

(defn- reject
  [kind message data]
  (k/result {:ok? false
             :error kind
             :text message
             :data data}))

(defn- result->map
  [kind result]
  (try
    (let [m (kedn/extract-map! (:text result))]
      (if (map? m)
        m
        (throw (ex-info "agent output must be an EDN map" {:parsed m}))))
    (catch Throwable t
      (throw (ex-info (str kind " output must be an EDN map")
                      {:text (:text result)
                       :result result}
                      t)))))

(defn- summary
  [r]
  (cond-> {:ok? (k/ok? r)}
    (:agent r) (assoc :agent (:agent r))
    (:text r)  (assoc :text (:text r))
    (:error r) (assoc :error (:error r))))

(defn- collect
  [input source-result]
  (pr-str (cond-> {:input input
                   :results (mapv summary (:results source-result))}
            (contains? source-result :subtasks)
            (assoc :subtasks (:subtasks source-result)))))

;; --- canonical nodes --------------------------------------------------------

(defmethod run-node :agent
  [runner agent input opts]
  (k/run-agent runner agent input opts))

(defn- step-result
  [name reply]
  (cond
    (and (map? reply) (= :result (:karcarthy/type reply)))
    reply

    (map? reply)
    (k/result (cond-> reply
                (not (contains? reply :step)) (assoc :step name)
                (not (contains? reply :raw)) (assoc :raw {:step name})))

    :else
    (k/result {:step name
               :text (str reply)
               :raw  {:step name}})))

(defmethod run-node :step
  [_runner {:keys [f name context?] :as node} input opts]
  (let [name  (or name "step")
        reply (if context?
                (f {:input input :opts opts :node (dissoc node :f)})
                (f input))]
    (step-result name reply)))

(defmethod run-node :pipe
  [runner {:keys [steps]} input opts]
  (loop [input input, indexed-steps (map-indexed vector steps), last-result nil]
    (if (empty? indexed-steps)
      (or last-result (k/result {:ok? true :text input :empty-pipe? true}))
      (let [[idx step] (first indexed-steps)
            r          (safe-run runner step input (descend opts :steps idx))]
        (if (k/ok? r)
          (recur (:text r) (rest indexed-steps) r)
          r)))))                                    ; short-circuit on failure

(defn- branch! [runner {:keys [branches max-concurrency]} input opts]
  (let [results  (dispatch max-concurrency
                           (fn [[idx branch]]
                             (safe-run runner branch input (descend opts :branches idx)))
                           (map-indexed vector branches))]
    (k/result {:ok?      (every? k/ok? results)
               :results  results
               :text     (str/join "\n\n" (keep :text results))})))

(defn- route! [runner source routes default input opts]
  (let [r (safe-run runner source input (descend opts :source))]
    (if-not (k/ok? r)
      r
      (try
        (let [m        (result->map :route r)
              label    (:route m)
              workflow (or (get routes label) default)]
          (cond
            (not (contains? m :route))
            (reject :invalid-route
                    "route output must contain :route"
                    {:output m :result r})

            workflow
            (if (contains? routes label)
              (safe-run runner workflow input (descend opts :routes label))
              (safe-run runner workflow input (descend opts :default)))

            :else
            (k/result {:ok? false :error :no-route :label label
                       :text (str "no route for label: " (pr-str label))})))
        (catch Throwable t
          (reject :invalid-route (.getMessage t) {:result r}))))))

(defn- judge!
  "Run `evaluator` against a draft, returning {:accept? :feedback :evaluation}."
  [runner evaluator draft input opts]
  (let [prompt (str "INPUT:\n" input "\n\nDRAFT:\n" (:text draft)
                    "\n\nReply with EDN only, either:"
                    "\n{:accept? true}"
                    "\nor"
                    "\n{:accept? false :feedback \"specific, actionable feedback\"}")
        r      (safe-run runner evaluator prompt opts)]
    (if-not (k/ok? r)
      {:accept? false :feedback (:text r) :evaluation r}
      (try
        (let [m (result->map :evaluation r)]
          (cond
            (not (contains? m :accept?))
            {:accept? false :feedback "evaluation output must contain :accept?" :evaluation r}

            (not (boolean? (:accept? m)))
            {:accept? false :feedback "evaluation :accept? must be true or false" :evaluation r}

            (:accept? m)
            {:accept? true :feedback (:feedback m) :evaluation r}

            (string? (:feedback m))
            {:accept? false :feedback (:feedback m) :evaluation r}

            :else
            {:accept? false :feedback "rejected evaluations must include string :feedback" :evaluation r}))
        (catch Throwable t
          {:accept? false :feedback (.getMessage t) :evaluation r})))))

(defn- revise! [runner {:keys [worker evaluator max-rounds]} input opts]
  (loop [round 1, worker-input input]
    (let [draft (safe-run runner worker worker-input (descend opts :worker round))]
      (if-not (k/ok? draft)
        draft                                       ; worker failed; bail out
        (let [{:keys [accept? feedback]} (judge! runner evaluator draft input
                                                 (descend opts :evaluator round))]
          (if (or accept? (>= round max-rounds))
            (k/result (assoc draft :rounds round :accepted? (boolean accept?)))
            (recur (inc round)
                   (str "INPUT:\n" input
                        "\n\nYOUR PREVIOUS DRAFT:\n" (:text draft)
                        "\n\nFEEDBACK TO ADDRESS:\n" feedback
                        "\n\nProduce an improved version."))))))))

(defn- plan!
  "Turn the input into a vector of subtask strings via `planner`.

  The planner must reply with EDN `{:subtasks [\"...\"]}`."
  [runner planner input opts]
  (let [r (safe-run runner planner input (descend opts :planner))]
    (if-not (k/ok? r)
      r
      (try
        (let [m        (result->map :planner r)
              subtasks (:subtasks m)]
          (if (and (vector? subtasks) (every? string? subtasks))
            subtasks
            (throw (ex-info "planner output must be {:subtasks [string ...]}"
                            {:output m :result r}))))
        (catch Throwable t
          (reject :invalid-subtasks (.getMessage t) {:result r}))))))

(defn- delegate! [runner {:keys [planner worker max-concurrency]} input opts]
  (let [subtasks (plan! runner planner input opts)]
    (if (map? subtasks)
      subtasks
      (let [results (dispatch max-concurrency
                              (fn [[idx subtask]]
                                (safe-run runner worker subtask (descend opts :worker idx)))
                              (map-indexed vector subtasks))]
        (k/result {:ok?      (every? k/ok? results)
                   :subtasks subtasks
                   :results  results
                   :text     (str/join "\n\n" (keep :text results))})))))

(defn- continue! [runner source to prompt input opts]
  (let [r1 (safe-run runner source input (descend opts :source))]
    (if-not (k/ok? r1)
      r1                                              ; bail if the first agent failed
      (let [opts' (cond-> opts
                    (:session-id r1) (assoc :resume (:session-id r1)))]
        (safe-run runner to (or prompt (:text r1)) (descend opts' :to))))))

(defmethod run-node :branch
  [runner node input opts]
  (branch! runner node input opts))

(defmethod run-node :delegate
  [runner node input opts]
  (delegate! runner node input opts))

(defmethod run-node :revise
  [runner node input opts]
  (revise! runner node input opts))

(defmethod run-node :reduce
  [runner {:keys [source reducer] :as node} input opts]
  (if (and source reducer)
    (let [source-result  (safe-run runner source input (descend opts :source))
          reducer-result (safe-run runner reducer (collect input source-result)
                                   (descend opts :reducer))]
      (k/result (assoc reducer-result
                       :ok? (and (k/ok? source-result) (k/ok? reducer-result))
                       :source source-result
                       :reduced reducer-result)))
    (throw (ex-info "reduce workflow requires :source and :reducer" {:node node}))))

(defmethod run-node :route
  [runner {:keys [source routes default]} input opts]
  (route! runner source routes default input opts))

(defmethod run-node :continue
  [runner {:keys [source to prompt]} input opts]
  (continue! runner source to prompt input opts))

(defmethod run-node :default
  [_ node _ _]
  (throw (ex-info "Not a runnable workflow node (missing or unknown :karcarthy/type)"
                  {:node node})))

(defn run
  "Interpret `workflow` through `runner`, starting from `input` (a string).
  Returns a `karcarthy.core` result map. `workflow` may be an agent or any
  composite node."
  ([runner workflow input] (run runner workflow input {}))
  ([runner workflow input opts]
   (if-not (:observe opts)
     (run-node runner workflow input opts)
     (let [span-id        (span-id)
           parent-span-id (:karcarthy/parent-span-id opts)
           started-ns     (System/nanoTime)
           opts'          (assoc opts :karcarthy/parent-span-id span-id)]
       (observe! opts (event :start span-id parent-span-id workflow opts))
       (try
         (let [result (run-node runner workflow input opts')]
           (observe! opts (event :finish span-id parent-span-id workflow
                                 (assoc opts
                                        :duration-ms (duration-ms started-ns)
                                        :ok? (k/ok? result))))
           result)
         (catch Throwable t
           (observe! opts (event :error span-id parent-span-id workflow
                                 (assoc opts
                                        :duration-ms (duration-ms started-ns)
                                        :ok? false
                                        :error (or (.getMessage t) (str t)))))
           (throw t)))))))

;; ---------------------------------------------------------------------------
;; DSL sugar
;; ---------------------------------------------------------------------------

(declare workflow?)

(defn- portable?
  [x]
  (not-any? fn?
            (tree-seq (fn [x]
                        (and (coll? x)
                             (not= :step (:karcarthy/type x))))
                      seq
                      x)))

(defn- extension? [x]
  (contains? (disj (set (keys (methods run-node))) :default
                   :agent :pipe :branch :delegate :reduce :revise :route :continue)
             (:karcarthy/type x)))

(defn workflow?
  "True if `x` is a runnable workflow.

  Core workflow nodes are validated recursively. Extension nodes must be
  registered with `run-node` and validated by a `node?` method."
  [x]
  (boolean
   (and (portable? x)
        (cond
          (k/agent? x) true
          (not (map? x)) false
          :else
          (case (:karcarthy/type x)
            :pipe
            (and (sequential? (:steps x))
                 (every? workflow? (:steps x)))

            :step
            (and (fn? (:f x))
                 (or (not (contains? x :name))
                     (string? (:name x)))
                 (or (not (contains? x :context?))
                     (boolean? (:context? x))))

            :branch
            (and (sequential? (:branches x))
                 (every? workflow? (:branches x)))

            :delegate
            (and (workflow? (:planner x))
                 (workflow? (:worker x)))

            :reduce
            (and (workflow? (:source x))
                 (workflow? (:reducer x)))

            :revise
            (and (workflow? (:worker x))
                 (workflow? (:evaluator x)))

            :route
            (and (workflow? (:source x))
                 (map? (:routes x))
                 (every? workflow? (vals (:routes x)))
                 (or (not (contains? x :default))
                     (workflow? (:default x))))

            :continue
            (and (workflow? (:source x))
                 (workflow? (:to x)))

            (and (extension? x)
                 (node? x)))))))

;; ---------------------------------------------------------------------------
;; Dynamic workflows
;; ---------------------------------------------------------------------------

(defn- dynamic-workflow?
  "Like `workflow?`, but allows `agent-ref` and `workflow-ref` leaves."
  [x]
  (cond
    (workflow? x) true
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
      :dynamic      (node? x)
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

(defn state
  "Create mutable state for one dynamic workflow run."
  [& {:keys [agents workflows history]}]
  (doseq [agent agents] (validate-agent agent))
  (doseq [[_ workflow] workflows] (validate-dynamic-workflow workflow))
  (atom {:agents    (into {} (clojure.core/map
                              (fn [agent]
                                [(name-key (:name agent)) agent])
                              agents))
         :workflows (into {} (clojure.core/map
                              (fn [[name workflow]]
                                [(name-key name) workflow])
                              workflows))
         :history   (vec history)}))

(defn snapshot
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

(defn refs->workflow
  "Resolve `agent-ref` and `workflow-ref` values against dynamic run state."
  ([state workflow] (refs->workflow state workflow #{}))
  ([state workflow seen]
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

       :pipe
       (update workflow :steps #(mapv (fn [w] (refs->workflow state w seen)) %))

       :branch
       (update workflow :branches #(mapv (fn [w] (refs->workflow state w seen)) %))

       :delegate
       (-> workflow
           (update :planner #(refs->workflow state % seen))
           (update :worker #(refs->workflow state % seen)))

       :reduce
       (-> workflow
           (update :source #(refs->workflow state % seen))
           (update :reducer #(refs->workflow state % seen)))

       :revise
       (-> workflow
           (update :worker #(refs->workflow state % seen))
           (update :evaluator #(refs->workflow state % seen)))

       :route
       (cond-> workflow
         true (update :source #(refs->workflow state % seen))
         true (update :routes (fn [routes]
                                (into {} (clojure.core/map
                                          (fn [[label w]]
                                            [label (refs->workflow state w seen)])
                                          routes))))
         (contains? workflow :default)
         (update :default #(refs->workflow state % seen)))

       :continue
       (-> workflow
           (update :source #(refs->workflow state % seen))
           (update :to #(refs->workflow state % seen)))

       :evolve
       (update workflow :agent #(refs->workflow state % seen))

       workflow))))

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

(defn text->op
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
      (k/result {:agent "dynamic" :kind :agent :name n
                 :text  (str "defined agent " n)}))

    (contains? op :workflow)
    (let [n        (name-key (:name op))
          workflow (validate-dynamic-workflow (:workflow op))]
      (when (str/blank? n)
        (throw (ex-info "define workflow requires :name" {:op op})))
      (swap! state assoc-in [:workflows n] workflow)
      (k/result {:agent "dynamic" :kind :workflow :name n
                 :text  (str "defined workflow " n)}))

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
        (k/result {:agent "dynamic" :kind :agent :name n
                   :text  (str "patched agent " n)}))

      (contains? op :workflow)
      (let [n       (name-key (op-target-name op :workflow))
            current (lookup-workflow state n)
            updated (validate-dynamic-workflow (merge current patch))]
        (swap! state assoc-in [:workflows n] updated)
        (k/result {:agent "dynamic" :kind :workflow :name n
                   :text  (str "patched workflow " n)}))

      :else
      (throw (ex-info "patch requires :agent or :workflow target" {:op op})))))

(defn- remove! [state op]
  (cond
    (contains? op :agent)
    (let [n (name-key (op-target-name op :agent))]
      (swap! state update :agents dissoc n)
      (k/result {:agent "dynamic" :kind :agent :name n :removed? true
                 :text  (str "removed agent " n)}))

    (contains? op :workflow)
    (let [n (name-key (op-target-name op :workflow))]
      (swap! state update :workflows dissoc n)
      (k/result {:agent "dynamic" :kind :workflow :name n :removed? true
                 :text  (str "removed workflow " n)}))

    :else
    (throw (ex-info "remove requires :agent or :workflow target" {:op op}))))

(defn- call-once [runner state op input opts]
  (cond
    (contains? op :agent)
    (let [target (:agent op)
          agent  (if (k/agent? target)
                   target
                   (lookup-agent state (op-target-name op :agent)))]
      (k/run-agent runner agent input opts))

    (contains? op :workflow)
    (let [target   (:workflow op)
          workflow (if (or (string? target) (keyword? target))
                     (lookup-workflow state target)
                     target)]
      (run runner (refs->workflow state workflow) input opts))

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
    (when-not (sequential? inputs)
      (throw (ex-info "spawn requires :inputs" {:op op})))
    (let [workflow (op-callable state op)
          indexed  (map-indexed vector inputs)
          results  (dispatch (:max-concurrency op)
                             (fn [[idx input]]
                               (safe-run runner workflow (str input)
                                         (descend opts :spawn idx)))
                             indexed)]
      (k/result {:agent   "dynamic"
                 :ok?     (every? k/ok? results)
                 :results results
                 :text    (str/join "\n\n" (keep :text results))}))))

(defn step!
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

(def dynamic-reference
  "Prompt fragment for dynamic workflow agents."
  (str/join
   "\n"
   ["You are the agent running one karcarthy dynamic workflow."
    "Output exactly one EDN op map. Do not output prose."
    ""
    "Ops:"
    "{:op :define :agent {:name \"writer\" :instructions \"...\" :model \"sonnet\"}}"
    "{:op :define :name \"draft\" :workflow WORKFLOW}"
    "{:op :patch :agent \"writer\" :merge {:instructions \"...\"}}"
    "{:op :patch :workflow \"draft\" :merge MAP}"
    "{:op :remove :agent \"writer\"}"
    "{:op :remove :workflow \"draft\"}"
    "{:op :call :agent \"writer\" :input \"...\"}"
    "{:op :call :workflow \"draft\" :input \"...\"}"
    "{:op :spawn :agent \"reviewer\" :inputs [\"a\" \"b\"]}"
    "{:op :spawn :workflow \"review\" :inputs [\"a\" \"b\"]}"
    "{:op :complete :text \"final answer\"}"
    ""
    "Late-bound refs inside workflows:"
    "{:karcarthy/type :agent-ref :name \"agent-name\"}"
    "{:karcarthy/type :workflow-ref :name \"workflow-name\"}"]))

(defn- state-view [state]
  (let [{:keys [agents workflows history]} @state]
    {:agents    (into {} (clojure.core/map
                          (fn [[n agent]]
                            [n (select-keys agent [:karcarthy/type :name
                                                    :description :instructions
                                                    :model :tools
                                                    :config])])
                          agents))
     :workflows workflows
     :history   history}))

(defn- dynamic-prompt [task state last-result step]
  (str dynamic-reference
       "\n\nTASK:\n" task
       "\n\nSTEP: " step
       "\n\nSTATE EDN:\n" (pr-str (state-view state))
       "\n\nLAST RESULT EDN:\n" (pr-str (compact-result last-result))
       "\n\nOutput exactly one EDN op now."))

(defmethod node? :dynamic
  [{:keys [agent max-steps]}]
  (and (k/agent? agent)
       (or (nil? max-steps)
           (and (integer? max-steps) (pos? max-steps)))))

(defmethod run-node :dynamic
  [runner {:keys [agent max-steps]} input opts]
  (let [st (or (:state opts) (state))]
    (loop [step 1, last-result nil]
      (if (and max-steps (> step max-steps))
        (k/result {:agent (:name agent)
                   :ok?   false
                   :error :max-steps
                   :text  (str "dynamic workflow exceeded max steps: " max-steps)
                   :state (snapshot st)})
        (let [agent-result (k/run-agent runner agent
                                        (dynamic-prompt input st last-result step)
                                        opts)]
          (if-not (k/ok? agent-result)
            (k/result (assoc agent-result :state (snapshot st)))
            (let [outcome (try
                            (let [op     (text->op (:text agent-result))
                                  result (step! runner st op opts)]
                              {:op op :result (assoc result
                                                     :state (snapshot st)
                                                     :steps step)})
                            (catch Throwable t
                              {:error t}))]
              (if-let [t (:error outcome)]
                (k/result {:agent (:name agent)
                           :ok?   false
                           :error (or (ex-message t) (str t))
                           :text  (:text agent-result)
                           :state (snapshot st)
                           :raw   {:agent agent-result}})
                (let [{:keys [op result]} outcome]
                  (if (= :complete (:op op))
                    result
                    (recur (inc step) result)))))))))))

(defmacro defworkflow
  "Define a var holding a workflow, validating at load time that it is runnable.

    (defworkflow support-desk
      (route triage {\"billing\"   billing
                     \"technical\" (pipe technical reviewer)}))"
  [sym workflow-form]
  `(def ~sym
     (let [f# ~workflow-form]
       (when-not (workflow? f#)
         (throw (ex-info "defworkflow: not a runnable workflow" {:sym '~sym :workflow f#})))
       f#)))

(ns karcarthy.orchestrate
  "Orchestration as data.

  A *workflow* is a plain Clojure value describing how to coordinate agents. It is
  either an agent (a leaf; see `karcarthy.core/agent`) or a composite built with
  functional constructors:

    pipe     run workflows in sequence, threading each result's :text into the next.
    map      run a collection of workflows on the same input, or run a worker over
             subtasks produced by a planner.
    reduce   run mapped work, then pass its EDN result summary to a reducer workflow.
    iterate  draft, critique, and retry until accepted or a round limit is reached.
    bind     choose or continue to the next workflow from a previous result.

  Because a workflow is data, you build, generate and serialize it with ordinary
  Clojure. Model-facing control messages are EDN maps:

    planner   {:subtasks [\"...\"]}
    evaluator {:accept? true} or {:accept? false :feedback \"...\"}
    router    {:route :some-route}

  `run` interprets a workflow through an adapter.

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

(defn pipe
  "Run `steps` in sequence, threading each result's :text into the next step."
  [& steps]
  {:karcarthy/type :pipe :steps (vec steps)})

(defn map
  "Map work over a collection.

  With one collection argument, each workflow in the collection runs on the same
  input. With planner and worker arguments, the planner must reply with EDN
  `{:subtasks [\"...\"]}` and the worker runs once per subtask. Options:
    :max-concurrency  max mapped calls running at once."
  [& args]
  (let [first-arg (first args)
        opts?     (or (= 1 (count args)) (keyword? (second args)))]
    (if (and (sequential? first-arg) opts?)
      (let [{:keys [max-concurrency] :as opts} (apply hash-map (rest args))]
        (when-let [unknown (seq (remove #{:max-concurrency} (keys opts)))]
          (throw (ex-info "map contains unknown options"
                          {:unknown (vec unknown)
                           :supported [:max-concurrency]})))
        (cond-> {:karcarthy/type :map :branches (vec first-arg)}
          max-concurrency (assoc :max-concurrency max-concurrency)))
      (let [[planner worker & opts] args]
        (let [{:keys [max-concurrency] :as opts} (apply hash-map opts)]
          (when-let [unknown (seq (remove #{:max-concurrency} (keys opts)))]
            (throw (ex-info "map contains unknown options"
                            {:unknown (vec unknown)
                             :supported [:max-concurrency]})))
          (cond-> {:karcarthy/type :map
                   :planner        planner
                   :worker         worker}
            max-concurrency (assoc :max-concurrency max-concurrency)))))))

(defn reduce
  "Run mapped work, then pass an EDN result summary to `reducer`.

  `mapped` is a `map` workflow, or a collection of workflows which is first
  turned into `(map workflows)`. `reducer` is a workflow. It receives a prompt
  whose body is EDN:

    {:input \"original input\"
     :subtasks [...]
     :results [{:agent \"...\" :ok? true :text \"...\"} ...]}"
  [mapped reducer & {:keys [max-concurrency]}]
  (let [node (if (sequential? mapped) (map mapped) mapped)]
    (case (:karcarthy/type node)
      :map
      {:karcarthy/type :reduce
       :mapped         (cond-> node
                         max-concurrency (assoc :max-concurrency max-concurrency))
       :reducer        reducer}

      (throw (ex-info "reduce expects mapped workflow data"
                      {:workflow mapped :type (:karcarthy/type node)})))))

(defn iterate
  "Run a worker/evaluator loop until accepted or `:max-rounds` is reached.

  The evaluator must reply with EDN:
    {:accept? true}
    {:accept? false :feedback \"what to improve\"}"
  [worker evaluator & {:keys [max-rounds] :or {max-rounds 3}}]
  {:karcarthy/type :iterate :worker worker :evaluator evaluator :max-rounds max-rounds})

(defn bind
  "Run `source`, then bind its result to the next workflow.

  When `next` is a choice map, `source` picks the key and the selected workflow
  receives the original input. The source must reply with EDN `{:route key}` and
  matching is exact. Otherwise `next` is treated as the continuation workflow
  and receives `source`'s result text, preserving session ids when the adapter
  supports them."
  [source next & {:keys [default prompt] :as opts}]
  (if (and (map? next) (not (contains? next :karcarthy/type)))
    (if (contains? opts :default)
      {:karcarthy/type :bind :source source :routes next :default default}
      {:karcarthy/type :bind :source source :routes next})
    (if (contains? opts :prompt)
      {:karcarthy/type :bind :source source :to next :prompt prompt}
      {:karcarthy/type :bind :source source :to next})))

;; ---------------------------------------------------------------------------
;; Interpreter
;; ---------------------------------------------------------------------------

(declare run)

(defmulti run-node
  "Execute one workflow node, dispatching on (:karcarthy/type node). This is the
  interpreter's extension point: teach it a new node by adding a constructor and
  a `(defmethod run-node :your-type [adapter node input opts] ...)` returning a
  `karcarthy.core` result. See `karcarthy.self` for examples (`:evolve`)."
  (fn [_adapter node _input _opts] (:karcarthy/type node)))

(defmulti extension-workflow?
  "Validate a non-core workflow node for `workflow?`.

  Extension namespaces that add `run-node` methods should also add an
  `extension-workflow?` method for the same `:karcarthy/type`; otherwise
  `workflow?` will reject the node."
  (fn [node] (:karcarthy/type node)))

(defmethod extension-workflow? :default [_] false)

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

(defn- child-opts
  [opts & path-segments]
  (if (:observe opts)
    (update opts :karcarthy/path (fnil into []) path-segments)
    opts))

(defn- workflow-event
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
  [adapter workflow input opts]
  (try
    (run adapter workflow input opts)
    (catch Throwable t
      (k/result {:ok?       false
                 :text      nil
                 :error     (or (.getMessage t) (str t))
                 :exception (.getName (class t))}))))

(defn- bounded-pmap
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

(defn- match-route
  "Resolve the workflow for `label` in `routes` by exact EDN value."
  [routes label]
  (get routes label))

(defn- invalid-control
  [kind message data]
  (k/result {:ok? false
             :error kind
             :text message
             :data data}))

(defn- read-control-map
  [kind result]
  (try
    (let [m (kedn/extract-map (:text result))]
      (if (map? m)
        m
        (throw (ex-info "control output must be an EDN map" {:parsed m}))))
    (catch Throwable t
      (throw (ex-info (str kind " output must be an EDN map")
                      {:text (:text result)
                       :result result}
                      t)))))

(defn- result-summary
  [r]
  (cond-> {:ok? (k/ok? r)}
    (:agent r) (assoc :agent (:agent r))
    (:text r)  (assoc :text (:text r))
    (:error r) (assoc :error (:error r))))

(defn- reduce-input
  [input mapped-result]
  (pr-str (cond-> {:input input
                   :results (mapv result-summary (:results mapped-result))}
            (contains? mapped-result :subtasks)
            (assoc :subtasks (:subtasks mapped-result)))))

;; --- canonical nodes --------------------------------------------------------

(defmethod run-node :agent
  [adapter agent input opts]
  (k/run-agent adapter agent input opts))

(defmethod run-node :pipe
  [adapter {:keys [steps]} input opts]
  (loop [input input, indexed-steps (map-indexed vector steps), last-result nil]
    (if (empty? indexed-steps)
      (or last-result (k/result {:ok? true :text input :empty-pipe? true}))
      (let [[idx step] (first indexed-steps)
            r          (safe-run adapter step input (child-opts opts :steps idx))]
        (if (k/ok? r)
          (recur (:text r) (rest indexed-steps) r)
          r)))))                                    ; short-circuit on failure

(defn- run-branch-map [adapter {:keys [branches max-concurrency]} input opts]
  (let [results  (bounded-pmap max-concurrency
                               (fn [[idx branch]]
                                 (safe-run adapter branch input (child-opts opts :branches idx)))
                               (map-indexed vector branches))]
    (k/result {:ok?      (every? k/ok? results)
               :results  results
               :text     (str/join "\n\n" (keep :text results))})))

(defn- run-route [adapter source routes default input opts]
  (let [r (safe-run adapter source input (child-opts opts :source))]
    (if-not (k/ok? r)
      r
      (try
        (let [m        (read-control-map :route r)
              label    (:route m)
              workflow (or (match-route routes label) default)]
          (cond
            (not (contains? m :route))
            (invalid-control :invalid-route
                             "route output must contain :route"
                             {:output m :result r})

            workflow
            (if (contains? routes label)
              (safe-run adapter workflow input (child-opts opts :routes label))
              (safe-run adapter workflow input (child-opts opts :default)))

            :else
            (k/result {:ok? false :error :no-route :label label
                       :text (str "no route for label: " (pr-str label))})))
        (catch Throwable t
          (invalid-control :invalid-route (.getMessage t) {:result r}))))))

(defn- evaluate
  "Run `evaluator` against a draft, returning {:accept? :feedback :evaluation}."
  [adapter evaluator draft input opts]
  (let [prompt (str "INPUT:\n" input "\n\nDRAFT:\n" (:text draft)
                    "\n\nReply with EDN only, either:"
                    "\n{:accept? true}"
                    "\nor"
                    "\n{:accept? false :feedback \"specific, actionable feedback\"}")
        r      (safe-run adapter evaluator prompt opts)]
    (if-not (k/ok? r)
      {:accept? false :feedback (:text r) :evaluation r}
      (try
        (let [m (read-control-map :evaluation r)]
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

(defn- run-iterate [adapter {:keys [worker evaluator max-rounds]} input opts]
  (loop [round 1, worker-input input]
    (let [draft (safe-run adapter worker worker-input (child-opts opts :worker round))]
      (if-not (k/ok? draft)
        draft                                       ; worker failed; bail out
        (let [{:keys [accept? feedback]} (evaluate adapter evaluator draft input
                                                   (child-opts opts :evaluator round))]
          (if (or accept? (>= round max-rounds))
            (k/result (assoc draft :rounds round :accepted? (boolean accept?)))
            (recur (inc round)
                   (str "INPUT:\n" input
                        "\n\nYOUR PREVIOUS DRAFT:\n" (:text draft)
                        "\n\nFEEDBACK TO ADDRESS:\n" feedback
                        "\n\nProduce an improved version."))))))))

(defn- plan-subtasks
  "Turn the input into a vector of subtask strings via `planner`.

  The planner must reply with EDN `{:subtasks [\"...\"]}`."
  [adapter planner input opts]
  (let [r (safe-run adapter planner input (child-opts opts :planner))]
    (if-not (k/ok? r)
      r
      (try
        (let [m        (read-control-map :planner r)
              subtasks (:subtasks m)]
          (if (and (vector? subtasks) (every? string? subtasks))
            subtasks
            (throw (ex-info "planner output must be {:subtasks [string ...]}"
                            {:output m :result r}))))
        (catch Throwable t
          (invalid-control :invalid-subtasks (.getMessage t) {:result r}))))))

(defn- run-planned-map [adapter {:keys [planner worker max-concurrency]} input opts]
  (let [subtasks (plan-subtasks adapter planner input opts)]
    (if (map? subtasks)
      subtasks
      (let [results (bounded-pmap max-concurrency
                                  (fn [[idx subtask]]
                                    (safe-run adapter worker subtask (child-opts opts :worker idx)))
                                  (map-indexed vector subtasks))]
        (k/result {:ok?      (every? k/ok? results)
                   :subtasks subtasks
                   :results  results
                   :text     (str/join "\n\n" (keep :text results))})))))

(defn- run-continuation [adapter source to prompt input opts]
  (let [r1 (safe-run adapter source input (child-opts opts :source))]
    (if-not (k/ok? r1)
      r1                                              ; bail if the first agent failed
      (let [opts' (cond-> opts
                    (:session-id r1) (assoc :resume (:session-id r1)))]
        (safe-run adapter to (or prompt (:text r1)) (child-opts opts' :to))))))

(defmethod run-node :map
  [adapter {:keys [branches planner worker] :as node} input opts]
  (cond
    branches
    (run-branch-map adapter node input opts)

    (and planner worker)
    (run-planned-map adapter node input opts)

    :else
    (throw (ex-info "map workflow requires :branches or :planner/:worker"
                    {:node node}))))

(defmethod run-node :iterate
  [adapter node input opts]
  (run-iterate adapter node input opts))

(defmethod run-node :reduce
  [adapter {:keys [mapped reducer] :as node} input opts]
  (if (and mapped reducer)
    (let [mapped-result  (safe-run adapter mapped input (child-opts opts :mapped))
          reducer-result (safe-run adapter reducer (reduce-input input mapped-result)
                                   (child-opts opts :reducer))]
      (k/result (assoc reducer-result
                       :ok? (and (k/ok? mapped-result) (k/ok? reducer-result))
                       :mapped mapped-result
                       :reduced reducer-result)))
    (throw (ex-info "reduce workflow requires :mapped and :reducer" {:node node}))))

(defmethod run-node :bind
  [adapter {:keys [source routes default to prompt] :as node} input opts]
  (cond
    routes
    (run-route adapter source routes default input opts)

    to
    (run-continuation adapter source to prompt input opts)

    :else
    (throw (ex-info "bind workflow requires :routes or :to" {:node node}))))

(defmethod run-node :default
  [_ node _ _]
  (throw (ex-info "Not a runnable workflow node (missing or unknown :karcarthy/type)"
                  {:node node})))

(defn run
  "Interpret `workflow` through `adapter`, starting from `input` (a string).
  Returns a `karcarthy.core` result map. `workflow` may be an agent or any
  composite node."
  ([adapter workflow input] (run adapter workflow input {}))
  ([adapter workflow input opts]
   (if-not (:observe opts)
     (run-node adapter workflow input opts)
     (let [span-id        (span-id)
           parent-span-id (:karcarthy/parent-span-id opts)
           started-ns     (System/nanoTime)
           opts'          (assoc opts :karcarthy/parent-span-id span-id)]
       (observe! opts (workflow-event :start span-id parent-span-id workflow opts))
       (try
         (let [result (run-node adapter workflow input opts')]
           (observe! opts (workflow-event :finish span-id parent-span-id workflow
                                          (assoc opts
                                                 :duration-ms (duration-ms started-ns)
                                                 :ok? (k/ok? result))))
           result)
         (catch Throwable t
           (observe! opts (workflow-event :error span-id parent-span-id workflow
                                          (assoc opts
                                                 :duration-ms (duration-ms started-ns)
                                                 :ok? false
                                                 :error (or (.getMessage t) (str t)))))
           (throw t)))))))

;; ---------------------------------------------------------------------------
;; DSL sugar
;; ---------------------------------------------------------------------------

(declare workflow?)

(defn- host-function-free?
  [x]
  (not-any? fn? (tree-seq coll? seq x)))

(defn- extension-node-type? [x]
  (contains? (disj (set (keys (methods run-node))) :default
                   :agent :pipe :map :reduce :iterate :bind)
             (:karcarthy/type x)))

(defn workflow?
  "True if `x` is a runnable workflow.

  Core workflow nodes are validated recursively. Extension nodes must be
  registered with `run-node` and validated by an `extension-workflow?` method."
  [x]
  (boolean
   (and (host-function-free? x)
        (cond
          (k/agent? x) true
          (not (map? x)) false
          :else
          (case (:karcarthy/type x)
            :pipe
            (and (sequential? (:steps x))
                 (every? workflow? (:steps x)))

            :map
            (or (and (sequential? (:branches x))
                     (every? workflow? (:branches x)))
                (and (workflow? (:planner x))
                     (workflow? (:worker x))))

            :reduce
            (and (workflow? (:mapped x))
                 (workflow? (:reducer x)))

            :iterate
            (and (workflow? (:worker x))
                 (workflow? (:evaluator x)))

            :bind
            (or (and (workflow? (:source x))
                     (map? (:routes x))
                     (every? workflow? (vals (:routes x)))
                     (or (not (contains? x :default))
                         (workflow? (:default x))))
                (and (workflow? (:source x))
                     (workflow? (:to x))))

            (and (extension-node-type? x)
                 (extension-workflow? x)))))))

(defmacro defworkflow
  "Define a var holding a workflow, validating at load time that it is runnable.

    (defworkflow support-desk
      (bind triage {\"billing\"   billing
                    \"technical\" (pipe technical reviewer)}))"
  [sym workflow-form]
  `(def ~sym
     (let [f# ~workflow-form]
       (when-not (workflow? f#)
         (throw (ex-info "defworkflow: not a runnable workflow" {:sym '~sym :workflow f#})))
       f#)))

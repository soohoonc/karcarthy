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
  the adapter supports them. Options:
    :prompt  override the prompt sent to `to`."
  [source to & {:keys [prompt] :as opts}]
  (if (contains? opts :prompt)
    {:karcarthy/type :continue :source source :to to :prompt prompt}
    {:karcarthy/type :continue :source source :to to}))

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
  [adapter workflow input opts]
  (try
    (run adapter workflow input opts)
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

(defn- control!
  [kind result]
  (try
    (let [m (kedn/extract-map! (:text result))]
      (if (map? m)
        m
        (throw (ex-info "control output must be an EDN map" {:parsed m}))))
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
  [adapter agent input opts]
  (k/run-agent adapter agent input opts))

(defmethod run-node :pipe
  [adapter {:keys [steps]} input opts]
  (loop [input input, indexed-steps (map-indexed vector steps), last-result nil]
    (if (empty? indexed-steps)
      (or last-result (k/result {:ok? true :text input :empty-pipe? true}))
      (let [[idx step] (first indexed-steps)
            r          (safe-run adapter step input (descend opts :steps idx))]
        (if (k/ok? r)
          (recur (:text r) (rest indexed-steps) r)
          r)))))                                    ; short-circuit on failure

(defn- branch! [adapter {:keys [branches max-concurrency]} input opts]
  (let [results  (dispatch max-concurrency
                           (fn [[idx branch]]
                             (safe-run adapter branch input (descend opts :branches idx)))
                           (map-indexed vector branches))]
    (k/result {:ok?      (every? k/ok? results)
               :results  results
               :text     (str/join "\n\n" (keep :text results))})))

(defn- route! [adapter source routes default input opts]
  (let [r (safe-run adapter source input (descend opts :source))]
    (if-not (k/ok? r)
      r
      (try
        (let [m        (control! :route r)
              label    (:route m)
              workflow (or (get routes label) default)]
          (cond
            (not (contains? m :route))
            (reject :invalid-route
                    "route output must contain :route"
                    {:output m :result r})

            workflow
            (if (contains? routes label)
              (safe-run adapter workflow input (descend opts :routes label))
              (safe-run adapter workflow input (descend opts :default)))

            :else
            (k/result {:ok? false :error :no-route :label label
                       :text (str "no route for label: " (pr-str label))})))
        (catch Throwable t
          (reject :invalid-route (.getMessage t) {:result r}))))))

(defn- judge!
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
        (let [m (control! :evaluation r)]
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

(defn- revise! [adapter {:keys [worker evaluator max-rounds]} input opts]
  (loop [round 1, worker-input input]
    (let [draft (safe-run adapter worker worker-input (descend opts :worker round))]
      (if-not (k/ok? draft)
        draft                                       ; worker failed; bail out
        (let [{:keys [accept? feedback]} (judge! adapter evaluator draft input
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
  [adapter planner input opts]
  (let [r (safe-run adapter planner input (descend opts :planner))]
    (if-not (k/ok? r)
      r
      (try
        (let [m        (control! :planner r)
              subtasks (:subtasks m)]
          (if (and (vector? subtasks) (every? string? subtasks))
            subtasks
            (throw (ex-info "planner output must be {:subtasks [string ...]}"
                            {:output m :result r}))))
        (catch Throwable t
          (reject :invalid-subtasks (.getMessage t) {:result r}))))))

(defn- delegate! [adapter {:keys [planner worker max-concurrency]} input opts]
  (let [subtasks (plan! adapter planner input opts)]
    (if (map? subtasks)
      subtasks
      (let [results (dispatch max-concurrency
                              (fn [[idx subtask]]
                                (safe-run adapter worker subtask (descend opts :worker idx)))
                              (map-indexed vector subtasks))]
        (k/result {:ok?      (every? k/ok? results)
                   :subtasks subtasks
                   :results  results
                   :text     (str/join "\n\n" (keep :text results))})))))

(defn- continue! [adapter source to prompt input opts]
  (let [r1 (safe-run adapter source input (descend opts :source))]
    (if-not (k/ok? r1)
      r1                                              ; bail if the first agent failed
      (let [opts' (cond-> opts
                    (:session-id r1) (assoc :resume (:session-id r1)))]
        (safe-run adapter to (or prompt (:text r1)) (descend opts' :to))))))

(defmethod run-node :branch
  [adapter node input opts]
  (branch! adapter node input opts))

(defmethod run-node :delegate
  [adapter node input opts]
  (delegate! adapter node input opts))

(defmethod run-node :revise
  [adapter node input opts]
  (revise! adapter node input opts))

(defmethod run-node :reduce
  [adapter {:keys [source reducer] :as node} input opts]
  (if (and source reducer)
    (let [source-result  (safe-run adapter source input (descend opts :source))
          reducer-result (safe-run adapter reducer (collect input source-result)
                                   (descend opts :reducer))]
      (k/result (assoc reducer-result
                       :ok? (and (k/ok? source-result) (k/ok? reducer-result))
                       :source source-result
                       :reduced reducer-result)))
    (throw (ex-info "reduce workflow requires :source and :reducer" {:node node}))))

(defmethod run-node :route
  [adapter {:keys [source routes default]} input opts]
  (route! adapter source routes default input opts))

(defmethod run-node :continue
  [adapter {:keys [source to prompt]} input opts]
  (continue! adapter source to prompt input opts))

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
       (observe! opts (event :start span-id parent-span-id workflow opts))
       (try
         (let [result (run-node adapter workflow input opts')]
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
  (not-any? fn? (tree-seq coll? seq x)))

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

(ns karcarthy.orchestrate
  "Orchestration as data.

  A *workflow* is a plain Clojure value describing how to coordinate agents. It is
  either an agent (a leaf; see `karcarthy.core/agent`) or a composite built with
  functional constructors:

    pipe     run workflows in sequence, threading each result's :text into the next.
    map      run a collection of workflows on the same input, or run a worker over
             subtasks produced by a planner.
    reduce   combine mapped results.
    iterate  draft, critique, and retry until accepted or a round limit is reached.
    bind     choose or continue to the next workflow from a previous result.

  Because a workflow is data, you build, generate and serialize it with ordinary
  Clojure. `run` interprets a workflow through an adapter.

  Every workflow run returns a `karcarthy.core` result map, so workflows compose: the
  output of one is valid input to another. Composite nodes are fault-isolated,
  so a child that throws becomes a not-ok result instead of crashing the run
  (see `safe-run`)."
  (:refer-clojure :exclude [iterate map reduce])
  (:require [clojure.string :as str]
            [karcarthy.core :as k]
            [karcarthy.otel :as otel])
  (:import [java.util.concurrent Executors Callable Future]))

;; ---------------------------------------------------------------------------
;; Constructors - build workflow data
;; ---------------------------------------------------------------------------

(defn chain
  "Compatibility name for `pipe`."
  [& steps]
  {:karcarthy/type :pipe :steps (vec steps)})

(defn parallel
  "Compatibility name for `map` over a collection of workflows."
  [& branches]
  {:karcarthy/type :map :branches (vec branches)})

(defn parallel*
  "Compatibility name for `map` over workflows with `:reduce` and concurrency options."
  [branches & {:keys [gather reduce max-concurrency]}]
  (let [gather (or gather reduce)]
    (cond-> {:karcarthy/type :map :branches (vec branches)}
      gather          (assoc :reduce gather)
      max-concurrency (assoc :max-concurrency max-concurrency))))

(defn route
  "Compatibility name for `bind` with a choice map."
  [router routes & {:keys [default]}]
  (cond-> {:karcarthy/type :bind :source router :routes routes}
    default (assoc :default default)))

(defn refine
  "Compatibility name for `iterate`."
  [worker evaluator & {:keys [max-rounds] :or {max-rounds 3}}]
  {:karcarthy/type :iterate :worker worker :evaluator evaluator :max-rounds max-rounds})

(defn orchestrate
  "Compatibility name for `map` with planner and worker arguments."
  [planner worker & {:keys [synthesize reduce max-concurrency] :or {max-concurrency 16}}]
  (let [synthesize (or synthesize reduce)]
    (cond-> {:karcarthy/type  :map
             :planner         planner
             :worker          worker
             :max-concurrency max-concurrency}
      synthesize (assoc :reduce synthesize))))

(defn handoff
  "Compatibility name for `bind` with two workflows."
  [from to & {:keys [prompt]}]
  {:karcarthy/type :bind :source from :to to :prompt prompt})

(defn pipe
  "Run `steps` in sequence, threading each result's :text into the next step."
  [& steps]
  (apply chain steps))

(defn map
  "Map work over a collection.

  With one collection argument, each workflow in the collection runs on the same
  input. With planner and worker arguments, the planner yields subtasks and the
  worker runs once per subtask. Options:
    :reduce           fn combining mapped results into a result/map.
    :max-concurrency  max mapped calls running at once."
  [& args]
  (let [first-arg (first args)
        opts?     (or (= 1 (count args)) (keyword? (second args)))]
    (if (and (sequential? first-arg) opts?)
      (let [{:keys [reduce max-concurrency]} (apply hash-map (rest args))]
        (parallel* first-arg :reduce reduce :max-concurrency max-concurrency))
      (let [[planner worker & opts] args]
        (apply orchestrate planner worker opts)))))

(defn reduce
  "Attach a reducer to mapped work.

  `f` receives the mapped result vector for `map` over branches, or
  [results input] for planner/worker `map`."
  [f mapped & {:keys [max-concurrency]}]
  (let [node (if (sequential? mapped) (map mapped) mapped)]
    (case (:karcarthy/type node)
      :map
      (cond-> (assoc node :reduce f)
        max-concurrency (assoc :max-concurrency max-concurrency))

      :parallel
      (cond-> (assoc node :gather f)
        max-concurrency (assoc :max-concurrency max-concurrency))

      :orchestrate
      (cond-> (assoc node :synthesize f)
        max-concurrency (assoc :max-concurrency max-concurrency))

      (throw (ex-info "reduce expects mapped workflow data"
                      {:workflow mapped :type (:karcarthy/type node)})))))

(defn iterate
  "Run a worker/evaluator loop until accepted or `:max-rounds` is reached."
  [worker evaluator & opts]
  (apply refine worker evaluator opts))

(defn bind
  "Run `source`, then bind its result to the next workflow.

  When `next` is a choice map, `source` picks the key and the selected workflow
  receives the original input. Otherwise `next` is treated as the continuation
  workflow and receives `source`'s result text, preserving session ids when the
  adapter supports them."
  [source next & {:keys [default prompt] :as opts}]
  (if (and (map? next) (not (contains? next :karcarthy/type)))
    (if (contains? opts :default)
      (route source next :default default)
      (route source next))
    (if (contains? opts :prompt)
      (handoff source next :prompt prompt)
      (handoff source next))))

;; ---------------------------------------------------------------------------
;; Interpreter
;; ---------------------------------------------------------------------------

(declare run)

(defmulti run-node
  "Execute one workflow node, dispatching on (:karcarthy/type node). This is the
  interpreter's extension point: teach it a new node by adding a constructor and
  a `(defmethod run-node :your-type [adapter node input opts] ...)` returning a
  `karcarthy.core` result. See `karcarthy.self` for examples (`:evolve`)."
  (fn [_runner node _input _opts] (:karcarthy/type node)))

;; --- shared helpers --------------------------------------------------------

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
  "Resolve the workflow for `label` in `routes`: exact match first, then - for
  string labels - case-insensitive exact and substring (label contains key)."
  [routes label]
  (or (get routes label)
      (when (string? label)
        (let [ll (str/lower-case (str/trim label))]
          (some (fn [[k v]]
                  (when (and (string? k)
                             (let [lk (str/lower-case k)]
                               (or (= lk ll) (str/includes? ll lk))))
                    v))
                routes)))))

;; --- nodes -----------------------------------------------------------------

(defmethod run-node :agent
  [runner agent input opts]
  (k/run-agent runner agent input opts))

(defmethod run-node :pipe
  [runner node input opts]
  (run-node runner (assoc node :karcarthy/type :chain) input opts))

(defmethod run-node :map
  [runner {:keys [branches planner worker reduce gather synthesize] :as node} input opts]
  (cond
    branches
    (run-node runner
              (cond-> (assoc node :karcarthy/type :parallel)
                (or reduce gather) (assoc :gather (or reduce gather)))
              input opts)

    (and planner worker)
    (run-node runner
              (cond-> (assoc node :karcarthy/type :orchestrate)
                (or reduce synthesize) (assoc :synthesize (or reduce synthesize)))
              input opts)

    :else
    (throw (ex-info "map workflow requires :branches or :planner/:worker"
                    {:node node}))))

(defmethod run-node :iterate
  [runner node input opts]
  (run-node runner (assoc node :karcarthy/type :refine) input opts))

(defmethod run-node :bind
  [runner {:keys [source router from routes default to prompt] :as node} input opts]
  (let [source (or source router from)]
    (cond
      routes
      (run-node runner {:karcarthy/type :route
                        :router         source
                        :routes         routes
                        :default        default}
                input opts)

      to
      (run-node runner {:karcarthy/type :handoff
                        :from           source
                        :to             to
                        :prompt         prompt}
                input opts)

      :else
      (throw (ex-info "bind workflow requires :routes or :to" {:node node})))))

(defmethod run-node :chain
  [runner {:keys [steps]} input opts]
  (loop [input input, indexed-steps (map-indexed vector steps), last-result nil]
    (if (empty? indexed-steps)
      (or last-result (k/result {:ok? true :text input :empty-chain? true}))
      (let [[idx step] (first indexed-steps)
            r          (safe-run runner step input (otel/with-child-path opts [:steps idx]))]
        (if (k/ok? r)
          (recur (:text r) (rest indexed-steps) r)
          r)))))                                    ; short-circuit on failure

(defmethod run-node :parallel
  [runner {:keys [branches gather max-concurrency]} input opts]
  (let [results  (bounded-pmap max-concurrency
                               (fn [[idx branch]]
                                 (safe-run runner branch input
                                           (otel/with-child-path opts [:branches idx])))
                               (map-indexed vector branches))
        gathered (when gather
                   (otel/with-function-span opts :parallel/gather gather
                     #(gather results)))]
    (k/result {:ok?      (every? k/ok? results)
               :results  results
               :gathered gathered
               :text     (or (:text gathered)
                             (str/join "\n\n" (keep :text results)))})))

(defmethod run-node :route
  [runner {:keys [router routes default]} input opts]
  (let [label (if (fn? router)
                (otel/with-function-span opts :route/router router
                  #(router input))
                (str/trim (str (:text (safe-run runner router input
                                                 (otel/with-child-path opts [:router]))))))
        workflow  (or (match-route routes label) default)
        route-path [(if (contains? routes label) :routes :route) label]]
    (if workflow
      (safe-run runner workflow input (otel/with-child-path opts route-path))
      (k/result {:ok? false :error :no-route :label label
                 :text (str "no route for label: " (pr-str label))}))))

(defn- evaluate
  "Run `evaluator` against a draft, returning {:accept? :feedback :evaluation}."
  [runner evaluator draft input opts]
  (if (fn? evaluator)
    (otel/with-function-span opts :refine/evaluator evaluator
      #(evaluator draft input))
    (let [prompt (str "INPUT:\n" input "\n\nDRAFT:\n" (:text draft)
                      "\n\nReply with exactly ACCEPT if the draft is good enough."
                      " Otherwise reply with specific, actionable feedback.")
          r      (safe-run runner evaluator prompt (otel/with-child-path opts [:evaluator]))
          t      (str/trim (str (:text r)))]
      {:accept?    (str/starts-with? (str/upper-case t) "ACCEPT")
       :feedback   t
       :evaluation r})))

(defmethod run-node :refine
  [runner {:keys [worker evaluator max-rounds]} input opts]
  (loop [round 1, worker-input input]
    (let [round-opts (otel/with-child-path opts [:round round])
          draft      (safe-run runner worker worker-input
                               (otel/with-child-path round-opts [:worker]))]
      (if-not (k/ok? draft)
        draft                                       ; worker failed; bail out
        (let [{:keys [accept? feedback]} (evaluate runner evaluator draft input round-opts)]
          (if (or accept? (>= round max-rounds))
            (k/result (assoc draft :rounds round :accepted? (boolean accept?)))
            (recur (inc round)
                   (str "INPUT:\n" input
                        "\n\nYOUR PREVIOUS DRAFT:\n" (:text draft)
                        "\n\nFEEDBACK TO ADDRESS:\n" feedback
                        "\n\nProduce an improved version."))))))))

(def ^:private list-marker #"^\s*(?:[-*•]|\d+[.)])\s+")

(defn- plan-subtasks
  "Turn the input into a vector of subtask strings via `planner` (a fn, or a
  workflow whose reply is parsed line-by-line)."
  [runner planner input opts]
  (if (fn? planner)
    (otel/with-function-span opts :orchestrate/planner planner
      #(vec (planner input)))
    (->> (str/split-lines (str (:text (safe-run runner planner input
                                                (otel/with-child-path opts [:planner])))))
         (clojure.core/map #(str/replace % list-marker ""))
         (clojure.core/map str/trim)
         (remove str/blank?)
         vec)))

(defmethod run-node :orchestrate
  [runner {:keys [planner worker synthesize max-concurrency]} input opts]
  (let [subtasks (plan-subtasks runner planner input opts)
        results  (bounded-pmap max-concurrency
                               (fn [[idx subtask]]
                                 (safe-run runner worker subtask
                                           (otel/with-child-path opts [:worker idx])))
                               (map-indexed vector subtasks))
        gathered (when synthesize
                   (otel/with-function-span opts :orchestrate/synthesize synthesize
                     #(synthesize results input)))]
    (k/result {:ok?      (every? k/ok? results)
               :subtasks subtasks
               :results  results
               :gathered gathered
               :text     (or (:text gathered)
                             (str/join "\n\n" (keep :text results)))})))

(defmethod run-node :handoff
  [runner {:keys [from to prompt]} input opts]
  (let [r1 (safe-run runner from input (otel/with-child-path opts [:from]))]
    (if-not (k/ok? r1)
      r1                                              ; bail if the first agent failed
      (let [opts' (cond-> opts
                    (:session-id r1) (assoc :resume (:session-id r1)))]
        (safe-run runner to (or prompt (:text r1)) (otel/with-child-path opts' [:to]))))))

(defmethod run-node :default
  [_ node _ _]
  (throw (ex-info "Not a runnable workflow node (missing or unknown :karcarthy/type)"
                  {:node node})))

(defn run
  "Interpret `workflow` through `adapter`, starting from `input` (a string).
  Returns a `karcarthy.core` result map. `workflow` may be an agent or any
  composite node."
  ([runner workflow input] (run runner workflow input {}))
  ([runner workflow input opts]
   (let [opts (otel/instrumented-opts runner opts)]
     (otel/with-workflow-span opts workflow input
       #(run-node runner workflow input opts)))))

(defn run-flow
  "Deprecated alias for `run`."
  ([runner flow input] (run runner flow input))
  ([runner flow input opts] (run runner flow input opts)))

;; ---------------------------------------------------------------------------
;; DSL sugar
;; ---------------------------------------------------------------------------

(defn workflow?
  "True if `x` is a runnable workflow: an agent leaf, or a node whose
  `:karcarthy/type` the interpreter handles."
  [x]
  (boolean (and (map? x)
                (or (k/agent? x)
                    (contains? (disj (set (keys (methods run-node))) :default)
                               (:karcarthy/type x))))))

(defn flow?
  "Deprecated alias for `workflow?`."
  [x]
  (workflow? x))

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

(defmacro defflow
  "Deprecated alias for `defworkflow`. Defines a var holding a workflow and
  validates at load time that it is runnable.

    (defflow support-desk
      (bind triage {\"billing\"   billing
                    \"technical\" (pipe technical reviewer)}))"
  [sym flow-form]
  `(def ~sym
     (let [f# ~flow-form]
       (when-not (workflow? f#)
         (throw (ex-info "defflow: not a runnable flow" {:sym '~sym :flow f#})))
       f#)))

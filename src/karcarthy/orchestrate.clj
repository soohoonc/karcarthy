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
            [karcarthy.core :as k])
  (:import [java.util.concurrent Executors Callable Future]))

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
  input. With planner and worker arguments, the planner yields subtasks and the
  worker runs once per subtask. Options:
    :reduce           fn combining mapped results into a result/map.
    :max-concurrency  max mapped calls running at once."
  [& args]
  (let [first-arg (first args)
        opts?     (or (= 1 (count args)) (keyword? (second args)))]
    (if (and (sequential? first-arg) opts?)
      (let [{:keys [reduce max-concurrency]} (apply hash-map (rest args))]
        (cond-> {:karcarthy/type :map :branches (vec first-arg)}
          reduce          (assoc :reduce reduce)
          max-concurrency (assoc :max-concurrency max-concurrency)))
      (let [[planner worker & opts] args]
        (let [{:keys [reduce max-concurrency]} (apply hash-map opts)]
          (cond-> {:karcarthy/type :map
                   :planner        planner
                   :worker         worker}
            reduce          (assoc :reduce reduce)
            max-concurrency (assoc :max-concurrency max-concurrency)))))))

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

      (throw (ex-info "reduce expects mapped workflow data"
                      {:workflow mapped :type (:karcarthy/type node)})))))

(defn iterate
  "Run a worker/evaluator loop until accepted or `:max-rounds` is reached."
  [worker evaluator & {:keys [max-rounds] :or {max-rounds 3}}]
  {:karcarthy/type :iterate :worker worker :evaluator evaluator :max-rounds max-rounds})

(defn bind
  "Run `source`, then bind its result to the next workflow.

  When `next` is a choice map, `source` picks the key and the selected workflow
  receives the original input. Otherwise `next` is treated as the continuation
  workflow and receives `source`'s result text, preserving session ids when the
  adapter supports them."
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

;; --- shared helpers --------------------------------------------------------

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

;; --- canonical nodes --------------------------------------------------------

(defmethod run-node :agent
  [adapter agent input opts]
  (k/run-agent adapter agent input opts))

(defmethod run-node :pipe
  [adapter {:keys [steps]} input opts]
  (loop [input input, indexed-steps (map-indexed vector steps), last-result nil]
    (if (empty? indexed-steps)
      (or last-result (k/result {:ok? true :text input :empty-pipe? true}))
      (let [[_idx step] (first indexed-steps)
            r           (safe-run adapter step input opts)]
        (if (k/ok? r)
          (recur (:text r) (rest indexed-steps) r)
          r)))))                                    ; short-circuit on failure

(defn- run-branch-map [adapter {:keys [branches reduce max-concurrency]} input opts]
  (let [results  (bounded-pmap max-concurrency
                               (fn [[idx branch]]
                                 (safe-run adapter branch input opts))
                               (map-indexed vector branches))
        reduced (when reduce (reduce results))]
    (k/result {:ok?      (every? k/ok? results)
               :results  results
               :reduced  reduced
               :text     (or (:text reduced)
                             (str/join "\n\n" (keep :text results)))})))

(defn- run-route [adapter source routes default input opts]
  (let [label (if (fn? source)
                (source input)
                (str/trim (str (:text (safe-run adapter source input opts)))))
        workflow  (or (match-route routes label) default)]
    (if workflow
      (safe-run adapter workflow input opts)
      (k/result {:ok? false :error :no-route :label label
                 :text (str "no route for label: " (pr-str label))}))))

(defn- evaluate
  "Run `evaluator` against a draft, returning {:accept? :feedback :evaluation}."
  [adapter evaluator draft input opts]
  (if (fn? evaluator)
    (evaluator draft input)
    (let [prompt (str "INPUT:\n" input "\n\nDRAFT:\n" (:text draft)
                      "\n\nReply with exactly ACCEPT if the draft is good enough."
                      " Otherwise reply with specific, actionable feedback.")
          r      (safe-run adapter evaluator prompt opts)
          t      (str/trim (str (:text r)))]
      {:accept?    (str/starts-with? (str/upper-case t) "ACCEPT")
       :feedback   t
       :evaluation r})))

(defn- run-iterate [adapter {:keys [worker evaluator max-rounds]} input opts]
  (loop [round 1, worker-input input]
    (let [draft (safe-run adapter worker worker-input opts)]
      (if-not (k/ok? draft)
        draft                                       ; worker failed; bail out
        (let [{:keys [accept? feedback]} (evaluate adapter evaluator draft input opts)]
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
  [adapter planner input opts]
  (if (fn? planner)
    (vec (planner input))
    (->> (str/split-lines (str (:text (safe-run adapter planner input
                                                opts))))
         (clojure.core/map #(str/replace % list-marker ""))
         (clojure.core/map str/trim)
         (remove str/blank?)
         vec)))

(defn- run-planned-map [adapter {:keys [planner worker reduce max-concurrency]} input opts]
  (let [subtasks (plan-subtasks adapter planner input opts)
        results  (bounded-pmap max-concurrency
                               (fn [[idx subtask]]
                                 (safe-run adapter worker subtask opts))
                               (map-indexed vector subtasks))
        reduced (when reduce (reduce results input))]
    (k/result {:ok?      (every? k/ok? results)
               :subtasks subtasks
               :results  results
               :reduced  reduced
               :text     (or (:text reduced)
                             (str/join "\n\n" (keep :text results)))})))

(defn- run-continuation [adapter source to prompt input opts]
  (let [r1 (safe-run adapter source input opts)]
    (if-not (k/ok? r1)
      r1                                              ; bail if the first agent failed
      (let [opts' (cond-> opts
                    (:session-id r1) (assoc :resume (:session-id r1)))]
        (safe-run adapter to (or prompt (:text r1)) opts')))))

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
   (run-node adapter workflow input opts)))

;; ---------------------------------------------------------------------------
;; DSL sugar
;; ---------------------------------------------------------------------------

(declare workflow?)

(defn- workflow-or-fn? [x]
  (or (fn? x) (workflow? x)))

(defn- extension-workflow? [x]
  (contains? (disj (set (keys (methods run-node))) :default
                   :agent :pipe :map :iterate :bind)
             (:karcarthy/type x)))

(defn workflow?
  "True if `x` is a runnable workflow.

  Core workflow nodes are validated recursively. Extension nodes registered with
  `run-node` are accepted as runnable, but their namespaces own their internal
  validation."
  [x]
  (boolean
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
           (and (workflow-or-fn? (:planner x))
                (workflow? (:worker x))))

       :iterate
       (and (workflow? (:worker x))
            (workflow-or-fn? (:evaluator x)))

       :bind
       (or (and (workflow-or-fn? (:source x))
                (map? (:routes x))
                (every? workflow? (vals (:routes x)))
                (or (not (contains? x :default))
                    (workflow? (:default x))))
           (and (workflow? (:source x))
                (workflow? (:to x))))

       (extension-workflow? x)))))

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

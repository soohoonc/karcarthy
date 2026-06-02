(ns karcarthy.orchestrate
  "Orchestration as data.

  A *workflow* is a plain Clojure value describing how to coordinate agents. It is
  either an agent (a leaf; see `karcarthy.core/agent`) or a composite node
  tagged with `:karcarthy/type`:

    :chain        run workflows in sequence, threading each result's :text into the
                  next; short-circuits on the first failure.
    :parallel     run workflows concurrently on the same input (bounded); gather.
    :route        pick one downstream workflow by a label from a router (a fn of the
                  input, or an agent whose reply is the label).
    :refine       evaluator-optimizer: draft, critique, repeat until accepted.
    :orchestrate  orchestrator-workers: plan subtasks, fan out, gather.
    :handoff      run one workflow, then hand off to another with shared session.

  Because a workflow is data, you build, generate and serialize it with ordinary
  Clojure. `run` interprets a workflow through an adapter.

  Every workflow run returns a `karcarthy.core` result map, so workflows compose: the
  output of one is valid input to another. Composite nodes are fault-isolated,
  so a child that throws becomes a not-ok result instead of crashing the run
  (see `safe-run`)."
  (:require [clojure.string :as str]
            [karcarthy.core :as k]
            [karcarthy.otel :as otel])
  (:import [java.util.concurrent Executors Callable Future]))

;; ---------------------------------------------------------------------------
;; Constructors - build workflow data
;; ---------------------------------------------------------------------------

(defn chain
  "A workflow that runs `steps` (agents or workflows) in sequence, threading the :text
  of each result into the next step's input. Returns the last result, or the
  first failing result (short-circuiting)."
  [& steps]
  {:karcarthy/type :chain :steps (vec steps)})

(defn parallel
  "A workflow that runs each of `branches` concurrently on the same input and
  returns a result whose :results is the vector of branch results (in order).
  Its :text is the branch texts joined by blank lines, and :ok? is true only if
  every branch succeeded. Concurrency is bounded (default 16).

  Use `parallel*` for `:gather` / `:max-concurrency` options."
  [& branches]
  {:karcarthy/type :parallel :branches (vec branches)})

(defn parallel*
  "Like `parallel` but with options:
    :gather           fn of the results vector -> a result/map; its :text
                      becomes the node's :text.
    :max-concurrency  max branches running at once (default 16)."
  [branches & {:keys [gather max-concurrency]}]
  (cond-> {:karcarthy/type :parallel :branches (vec branches)}
    gather          (assoc :gather gather)
    max-concurrency (assoc :max-concurrency max-concurrency)))

(defn route
  "A workflow that dispatches to one downstream workflow chosen by `router`:
    - if `router` is a fn, it is called with the input and must return a label;
    - otherwise `router` is run as a workflow and its result's :text is the label.
  `routes` maps labels to workflows. Matching is exact first, then (for string
  labels) case-insensitive and substring - so an agent that replies \"This is a
  billing question\" still matches the route \"billing\". `:default` (optional
  kwarg) is used when nothing matches."
  [router routes & {:keys [default]}]
  (cond-> {:karcarthy/type :route :router router :routes routes}
    default (assoc :default default)))

(defn refine
  "Evaluator-optimizer: a `worker` drafts an answer, an `evaluator` critiques it,
  and the worker revises using that critique - repeating until the evaluator
  accepts or `:max-rounds` (default 3) is reached. Returns the final worker
  result annotated with `:rounds` and `:accepted?`.

  `evaluator` is either:
    - a fn of [draft-result input] -> {:accept? boolean :feedback string}, or
    - a workflow/agent run on the draft; its reply is the verdict - accepted when the
      trimmed text begins with \"ACCEPT\" (case-insensitive), otherwise the whole
      reply is treated as feedback."
  [worker evaluator & {:keys [max-rounds] :or {max-rounds 3}}]
  {:karcarthy/type :refine :worker worker :evaluator evaluator :max-rounds max-rounds})

(defn orchestrate
  "Orchestrator-workers: a `planner` decomposes the input into subtasks, a
  `worker` workflow handles each subtask (fanned out, at most :max-concurrency at a
  time, default 16), and an optional
  `:synthesize` fn combines the worker results.

  `planner` is either a fn of input -> seq of subtask strings, or a workflow/agent
  whose reply is parsed into subtasks (one per non-blank line, with leading list
  markers like '-', '*', '1.' stripped). `:synthesize` is a fn of
  [results input] -> result/map; when present its :text becomes the node :text."
  [planner worker & {:keys [synthesize max-concurrency] :or {max-concurrency 16}}]
  (cond-> {:karcarthy/type  :orchestrate
           :planner         planner
           :worker          worker
           :max-concurrency max-concurrency}
    synthesize (assoc :synthesize synthesize)))

(defn handoff
  "Run `from`, then hand off to `to`, threading `from`'s session so `to` inherits
  its context on adapters that support sessions (e.g. claude-cli, via --resume).
  `to`'s input defaults to `from`'s :text; pass `:prompt` to override. On
  adapters without sessions the handoff still runs both workflows in sequence."
  [from to & {:keys [prompt]}]
  {:karcarthy/type :handoff :from from :to to :prompt prompt})

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
         (map #(str/replace % list-marker ""))
         (map str/trim)
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
      (route triage {\"billing\"   billing
                     \"technical\" (chain technical reviewer)}))"
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
      (route triage {\"billing\"   billing
                     \"technical\" (chain technical reviewer)}))"
  [sym flow-form]
  `(def ~sym
     (let [f# ~flow-form]
       (when-not (workflow? f#)
         (throw (ex-info "defflow: not a runnable flow" {:sym '~sym :flow f#})))
       f#)))

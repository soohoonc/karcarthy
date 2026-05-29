(ns karcarthy.orchestrate
  "Orchestration as data.

  A *flow* is a plain Clojure value describing how to coordinate agents. It is
  either an agent (a leaf — see `karcarthy.core/agent`) or a composite node
  tagged with `:karcarthy/type`:

    :chain        run flows in sequence, threading each result's :text into the
                  next; short-circuits on the first failure.
    :parallel     run flows concurrently on the same input (bounded); gather.
    :route        pick one downstream flow by a label from a router (a fn of the
                  input, or an agent whose reply is the label).
    :refine       evaluator-optimizer: draft, critique, repeat until accepted.
    :orchestrate  orchestrator-workers: plan subtasks, fan out, gather.
    :handoff      run one flow, then hand off to another with shared session.

  Because a flow is just data, you can build it with the constructors here,
  assemble it by hand, generate it programmatically, walk it with
  `clojure.walk`, serialize it to EDN, and diff two versions of it. `run-flow`
  is the interpreter that executes a flow against a `Harness`.

  Every flow run returns a `karcarthy.core` result map, so flows compose: the
  output of one is a valid input position for another. Composite nodes are
  fault-isolated — a child that throws becomes a not-ok result rather than
  crashing the whole run (see `safe-run`)."
  (:require [clojure.string :as str]
            [karcarthy.core :as k])
  (:import [java.util.concurrent Executors Callable Future]))

;; ---------------------------------------------------------------------------
;; Constructors — build flow data
;; ---------------------------------------------------------------------------

(defn chain
  "A flow that runs `steps` (agents or flows) in sequence, threading the :text
  of each result into the next step's input. Returns the last result, or the
  first failing result (short-circuiting)."
  [& steps]
  {:karcarthy/type :chain :steps (vec steps)})

(defn parallel
  "A flow that runs each of `branches` concurrently on the same input and
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
  "A flow that dispatches to one downstream flow chosen by `router`:
    - if `router` is a fn, it is called with the input and must return a label;
    - otherwise `router` is run as a flow and its result's :text is the label.
  `routes` maps labels to flows. Matching is exact first, then (for string
  labels) case-insensitive and substring — so an agent that replies \"This is a
  billing question\" still matches the route \"billing\". `:default` (optional
  kwarg) is used when nothing matches."
  [router routes & {:keys [default]}]
  (cond-> {:karcarthy/type :route :router router :routes routes}
    default (assoc :default default)))

(defn refine
  "Evaluator-optimizer: a `worker` drafts an answer, an `evaluator` critiques it,
  and the worker revises using that critique — repeating until the evaluator
  accepts or `:max-rounds` (default 3) is reached. Returns the final worker
  result annotated with `:rounds` and `:accepted?`.

  `evaluator` is either:
    - a fn of [draft-result input] -> {:accept? boolean :feedback string}, or
    - a flow/agent run on the draft; its reply is the verdict — accepted when the
      trimmed text begins with \"ACCEPT\" (case-insensitive), otherwise the whole
      reply is treated as feedback."
  [worker evaluator & {:keys [max-rounds] :or {max-rounds 3}}]
  {:karcarthy/type :refine :worker worker :evaluator evaluator :max-rounds max-rounds})

(defn orchestrate
  "Orchestrator-workers: a `planner` decomposes the input into subtasks, a
  `worker` flow handles each subtask (fanned out, at most :max-concurrency at a
  time — default 16, echoing the dynamic-workflows bound), and an optional
  `:synthesize` fn combines the worker results.

  `planner` is either a fn of input -> seq of subtask strings, or a flow/agent
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
  its context on harnesses that support sessions (e.g. claude-cli, via --resume).
  `to`'s input defaults to `from`'s :text; pass `:prompt` to override. On
  harnesses without sessions the handoff still runs both flows in sequence."
  [from to & {:keys [prompt]}]
  {:karcarthy/type :handoff :from from :to to :prompt prompt})

;; ---------------------------------------------------------------------------
;; Interpreter
;; ---------------------------------------------------------------------------

(declare run-flow)

(defmulti ^:private run-node
  "Execute one flow node. Dispatches on (:karcarthy/type node)."
  (fn [_harness node _input _opts] (:karcarthy/type node)))

;; --- shared helpers --------------------------------------------------------

(defn safe-run
  "Run a child flow, converting a thrown exception into a not-ok result so one
  bad branch can't crash a whole multi-agent run. Composite nodes run their
  children through this; top-level `run-flow` stays transparent (fail fast)."
  [harness flow input opts]
  (try
    (run-flow harness flow input opts)
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
  "Resolve the flow for `label` in `routes`: exact match first, then — for
  string labels — case-insensitive exact and substring (label contains key)."
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
  [harness agent input opts]
  (k/run-agent harness agent input opts))

(defmethod run-node :chain
  [harness {:keys [steps]} input opts]
  (loop [input input, steps steps, last-result nil]
    (if (empty? steps)
      (or last-result (k/result {:ok? true :text input :empty-chain? true}))
      (let [r (safe-run harness (first steps) input opts)]
        (if (k/ok? r)
          (recur (:text r) (rest steps) r)
          r)))))                                    ; short-circuit on failure

(defmethod run-node :parallel
  [harness {:keys [branches gather max-concurrency]} input opts]
  (let [results  (bounded-pmap max-concurrency
                               #(safe-run harness % input opts)
                               branches)
        gathered (when gather (gather results))]
    (k/result {:ok?      (every? k/ok? results)
               :results  results
               :gathered gathered
               :text     (or (:text gathered)
                             (str/join "\n\n" (keep :text results)))})))

(defmethod run-node :route
  [harness {:keys [router routes default]} input opts]
  (let [label (if (fn? router)
                (router input)
                (str/trim (str (:text (safe-run harness router input opts)))))
        flow  (or (match-route routes label) default)]
    (if flow
      (safe-run harness flow input opts)
      (k/result {:ok? false :error :no-route :label label
                 :text (str "no route for label: " (pr-str label))}))))

(defn- evaluate
  "Run `evaluator` against a draft, returning {:accept? :feedback :evaluation}."
  [harness evaluator draft input opts]
  (if (fn? evaluator)
    (evaluator draft input)
    (let [prompt (str "INPUT:\n" input "\n\nDRAFT:\n" (:text draft)
                      "\n\nReply with exactly ACCEPT if the draft is good enough."
                      " Otherwise reply with specific, actionable feedback.")
          r      (safe-run harness evaluator prompt opts)
          t      (str/trim (str (:text r)))]
      {:accept?    (str/starts-with? (str/upper-case t) "ACCEPT")
       :feedback   t
       :evaluation r})))

(defmethod run-node :refine
  [harness {:keys [worker evaluator max-rounds]} input opts]
  (loop [round 1, worker-input input]
    (let [draft (safe-run harness worker worker-input opts)]
      (if-not (k/ok? draft)
        draft                                       ; worker failed; bail out
        (let [{:keys [accept? feedback]} (evaluate harness evaluator draft input opts)]
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
  flow whose reply is parsed line-by-line)."
  [harness planner input opts]
  (if (fn? planner)
    (vec (planner input))
    (->> (str/split-lines (str (:text (safe-run harness planner input opts))))
         (map #(str/replace % list-marker ""))
         (map str/trim)
         (remove str/blank?)
         vec)))

(defmethod run-node :orchestrate
  [harness {:keys [planner worker synthesize max-concurrency]} input opts]
  (let [subtasks (plan-subtasks harness planner input opts)
        results  (bounded-pmap max-concurrency
                               #(safe-run harness worker % opts)
                               subtasks)
        gathered (when synthesize (synthesize results input))]
    (k/result {:ok?      (every? k/ok? results)
               :subtasks subtasks
               :results  results
               :gathered gathered
               :text     (or (:text gathered)
                             (str/join "\n\n" (keep :text results)))})))

(defmethod run-node :handoff
  [harness {:keys [from to prompt]} input opts]
  (let [r1 (safe-run harness from input opts)]
    (if-not (k/ok? r1)
      r1                                              ; bail if the first agent failed
      (let [opts' (cond-> opts
                    (:session-id r1) (assoc :resume (:session-id r1)))]
        (safe-run harness to (or prompt (:text r1)) opts')))))

(defmethod run-node :default
  [_ node _ _]
  (throw (ex-info "Not a runnable flow node (missing or unknown :karcarthy/type)"
                  {:node node})))

(defn run-flow
  "Interpret `flow` against `harness`, starting from `input` (a string). Returns
  a `karcarthy.core` result map. `flow` may be an agent or any composite node."
  ([harness flow input] (run-flow harness flow input {}))
  ([harness flow input opts] (run-node harness flow input opts)))

;; ---------------------------------------------------------------------------
;; DSL sugar
;; ---------------------------------------------------------------------------

(defn flow?
  "True if `x` is a runnable flow: an agent leaf, or a node whose
  `:karcarthy/type` the interpreter handles."
  [x]
  (boolean (and (map? x)
                (or (k/agent? x)
                    (contains? (disj (set (keys (methods run-node))) :default)
                               (:karcarthy/type x))))))

(defmacro defflow
  "Define a var holding a flow, validating at load time that it is runnable.

    (defflow support-desk
      (route triage {\"billing\"   billing
                     \"technical\" (chain technical reviewer)}))"
  [sym flow-form]
  `(def ~sym
     (let [f# ~flow-form]
       (when-not (flow? f#)
         (throw (ex-info "defflow: not a runnable flow" {:sym '~sym :flow f#})))
       f#)))

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
            [karcarthy.edn :as kedn]
            [karcarthy.observe :as obs])
  (:import [java.util.concurrent Executors Callable Future]))

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

  By default, `f` receives the flowing input string. With `:call? true`, `f`
  receives `{:input input :options options :node node}`. A step may return text, a
  partial result map, or a full result map. Step nodes are Clojure-local and are
  not EDN/JSON portable."
  [f & {:keys [name call?] :as opts}]
  (k/reject-unknown! "step" [:name :call?] opts)
  (cond-> {:karcarthy/type :step :f f}
    name (assoc :name (name-key name))
    call? (assoc :call? true)))

(defn branch
  "Run each workflow in `branches` on the same input. Options:
    :max-concurrency  max branch calls running at once."
  [branches & {:keys [max-concurrency] :as opts}]
  (k/reject-unknown! "branch" [:max-concurrency] opts)
  (cond-> {:karcarthy/type :branch :branches (vec branches)}
    max-concurrency (assoc :max-concurrency max-concurrency)))

(defn delegate
  "Ask `planner` for EDN `{:subtasks [\"...\"]}`, then run `worker` once per
  subtask. Options:
    :max-concurrency  max worker calls running at once."
  [planner worker & {:keys [max-concurrency] :as opts}]
  (k/reject-unknown! "delegate" [:max-concurrency] opts)
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
  [source reducer & {:keys [max-concurrency] :as opts}]
  (k/reject-unknown! "reduce" [:max-concurrency] opts)
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
  [worker evaluator & {:keys [max-rounds] :or {max-rounds 3} :as opts}]
  (k/reject-unknown! "revise" [:max-rounds] opts)
  {:karcarthy/type :revise :worker worker :evaluator evaluator :max-rounds max-rounds})

(defn route
  "Run `source`, read EDN `{:route key}`, then run the matching workflow from
  `routes` on the original input. Matching is exact. Options:
    :default  workflow to run when the label is not in `routes`."
  [source routes & {:keys [default] :as opts}]
  (k/reject-unknown! "route" [:default] opts)
  (cond-> {:karcarthy/type :route :source source :routes routes}
    (contains? opts :default) (assoc :default default)))

(defn continue
  "Run `source`, then send its result text to `to`, preserving session ids when
  the runner supports them. Options:
    :prompt  override the prompt sent to `to`."
  [source to & {:keys [prompt] :as opts}]
  (k/reject-unknown! "continue" [:prompt] opts)
  (cond-> {:karcarthy/type :continue :source source :to to}
    (contains? opts :prompt) (assoc :prompt prompt)))

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
;; Interpreter
;; ---------------------------------------------------------------------------

(declare run*)

(defmulti run-node
  "Execute one workflow node, dispatching on (:karcarthy/type node). This is the
  interpreter's extension point: teach it a new node by adding a constructor and
  a `(defmethod run-node :your-type [runner node input options] ...)` returning a
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

(defn- descend
  [opts & path-segments]
  (if (:observe opts)
    (update opts :karcarthy/path (fnil into []) path-segments)
    opts))

(defn- workflow-event
  [phase span-id parent-span-id workflow attrs]
  (let [type      (:karcarthy/type workflow)
        type-name (if type (name type) "unknown")]
    (obs/event phase span-id parent-span-id
               {:kind :workflow
                :name (str "karcarthy.workflow." type-name)
                :attributes {"karcarthy.kind"          "workflow"
                             "karcarthy.workflow.type" type-name}}
               attrs)))

(defn safe-run
  "Run a child workflow, converting a thrown exception into a not-ok result so one
  bad branch can't crash a whole multi-agent run. Composite nodes run their
  children through this; top-level `run` stays transparent (fail fast)."
  [runner workflow input opts]
  (try
    (run* runner workflow input opts)
    (catch Throwable t
      (k/result {:ok?       false
                 :text      nil
                 :error     (or (.getMessage t) (str t))
                 :exception (.getName (class t))}))))

(defn- bounded-pmap
  "Like `mapv`, but with at most `n` (default 16) calls of `f` in flight at
  once on a fixed thread pool, preserving order. `f` should not throw (wrap it
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

(defn- repair-prompt
  [input kind shape failed-text error]
  (str input
       "\n\nYour previous reply could not be used as " (name kind) " output."
       "\nError: " error
       "\nYour previous reply:\n" failed-text
       "\n\nReply with EDN only, matching: " shape))

(defn elicit!
  "Run `workflow` on `input` and coerce its EDN reply with `parse!` (a fn of
  the reply map that returns a value, or throws with a correctable message).

  Models flub EDN; a single malformed reply should not fail a whole node. On
  a parse or shape failure this re-asks up to `:edn-retries` times (default 1),
  appending the failed reply and its error so the model can correct itself.
  Returns {:result r :value v} on success, {:result r :error t} when retries
  are exhausted, and {:result r} when the run itself failed."
  [runner workflow input opts {:keys [kind shape parse!]}]
  (let [retries (long (get opts :edn-retries 1))]
    (loop [attempt 0, prompt input]
      (let [r (safe-run runner workflow prompt opts)]
        (if-not (k/ok? r)
          {:result r}
          (let [parsed (try
                         {:value (parse! (result->map kind r))}
                         (catch Throwable t {:error t}))]
            (cond
              (not (contains? parsed :error))
              {:result r :value (:value parsed)}

              (< attempt retries)
              (recur (inc attempt)
                     (repair-prompt input kind shape (:text r)
                                    (ex-message (:error parsed))))

              :else
              {:result r :error (:error parsed)})))))))

;; --- canonical nodes --------------------------------------------------------

(defmethod run-node :agent
  [runner agent input opts]
  (k/run-agent runner agent input opts))

(defn- step-result
  [name reply]
  (k/coerce-result reply {:step name
                          :raw  {:step name}}))

(defmethod run-node :step
  [_runner {:keys [f name call?] :as node} input opts]
  (let [name  (or name "step")
        reply (if call?
                (f {:input input :options opts :node (dissoc node :f)})
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

(defn- section-label [idx r]
  (or (:agent r) (:step r) (str "branch-" idx)))

(defn- sections
  "Join results into labeled sections, so a downstream workflow reading the
  flowing text can attribute each part - and see failures - instead of
  receiving anonymous prose. Structured access stays on :results."
  [results]
  (str/join "\n\n"
            (keep-indexed (fn [idx r]
                            (cond
                              (:text r)  (str "## " (section-label idx r) "\n" (:text r))
                              (:error r) (str "## " (section-label idx r) " (failed)\n" (:error r))))
                          results)))

(defn- branch! [runner {:keys [branches max-concurrency]} input opts]
  (let [results  (bounded-pmap max-concurrency
                               (fn [[idx branch]]
                                 (safe-run runner branch input (descend opts :branches idx)))
                               (map-indexed vector branches))]
    (k/result {:ok?      (every? k/ok? results)
               :results  results
               :text     (sections results)})))

(defn- route! [runner {:keys [source routes default]} input opts]
  (let [shape  (str "{:route <label>} where <label> is one of "
                    (pr-str (vec (keys routes)))
                    (when default
                      " (any other label falls back to the default workflow)"))
        parse! (fn [m]
                 (when-not (contains? m :route)
                   (throw (ex-info "route output must contain :route" {:output m})))
                 (let [label (:route m)]
                   (when-not (or (contains? routes label) default)
                     (throw (ex-info (str "no route for label: " (pr-str label))
                                     {:karcarthy/error :no-route :label label})))
                   label))
        {:keys [result error] :as parsed}
        (elicit! runner source input (descend opts :source)
                 {:kind :route :shape shape :parse! parse!})]
    (cond
      (not (k/ok? result)) result

      error
      (if (= :no-route (:karcarthy/error (ex-data error)))
        (k/result {:ok? false :error :no-route :label (:label (ex-data error))
                   :text (ex-message error)})
        (reject :invalid-route (ex-message error) {:result result}))

      :else
      (let [label (:value parsed)]
        (if (contains? routes label)
          (safe-run runner (get routes label) input (descend opts :routes label))
          (safe-run runner default input (descend opts :default)))))))

(defn- judge!
  "Run `evaluator` against a draft, returning {:accept? :feedback :evaluation}."
  [runner evaluator draft input opts]
  (let [prompt (str "INPUT:\n" input "\n\nDRAFT:\n" (:text draft)
                    "\n\nReply with EDN only, either:"
                    "\n{:accept? true}"
                    "\nor"
                    "\n{:accept? false :feedback \"specific, actionable feedback\"}")
        parse! (fn [m]
                 (cond
                   (not (contains? m :accept?))
                   (throw (ex-info "evaluation output must contain :accept?" {:output m}))

                   (not (boolean? (:accept? m)))
                   (throw (ex-info "evaluation :accept? must be true or false" {:output m}))

                   (and (not (:accept? m)) (not (string? (:feedback m))))
                   (throw (ex-info "rejected evaluations must include string :feedback" {:output m}))

                   :else
                   (select-keys m [:accept? :feedback])))
        {:keys [result error] :as parsed}
        (elicit! runner evaluator prompt opts
                 {:kind  :evaluation
                  :shape "{:accept? true} or {:accept? false :feedback \"...\"}"
                  :parse! parse!})]
    (cond
      (not (k/ok? result)) {:accept? false :feedback (:text result) :evaluation result}
      error                {:accept? false :feedback (ex-message error) :evaluation result}
      :else                (assoc (:value parsed) :evaluation result))))

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
  (let [parse! (fn [m]
                 (let [subtasks (:subtasks m)]
                   (when-not (and (vector? subtasks) (every? string? subtasks))
                     (throw (ex-info "planner output must be {:subtasks [string ...]}"
                                     {:output m})))
                   subtasks))
        {:keys [result error] :as parsed}
        (elicit! runner planner input (descend opts :planner)
                 {:kind :planner :shape "{:subtasks [\"...\"]}" :parse! parse!})]
    (cond
      (not (k/ok? result)) result
      error                (reject :invalid-subtasks (ex-message error) {:result result})
      :else                (:value parsed))))

(defn- delegate! [runner {:keys [planner worker max-concurrency]} input opts]
  (let [subtasks (plan! runner planner input opts)]
    (if (map? subtasks)
      subtasks
      (let [results (bounded-pmap max-concurrency
                                  (fn [[idx subtask]]
                                    (safe-run runner worker subtask (descend opts :worker idx)))
                                  (map-indexed vector subtasks))]
        (k/result {:ok?      (every? k/ok? results)
                   :subtasks subtasks
                   :results  results
                   :text     (str/join "\n\n" (keep :text results))})))))

(defn- continue! [runner {:keys [source to prompt]} input opts]
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
  [runner node input opts]
  (route! runner node input opts))

(defmethod run-node :continue
  [runner node input opts]
  (continue! runner node input opts))

(defmethod run-node :default
  [_ node _ _]
  (throw (ex-info "Not a runnable workflow node (missing or unknown :karcarthy/type)"
                  {:node node})))

(def ^:private run-request-keys
  #{:runner :workflow :input :options})

(defn- input->text
  [input]
  (cond
    (nil? input) ""
    (string? input) input
    (and (map? input) (contains? input :prompt))
    (let [prompt (:prompt input)
          data   (not-empty (dissoc input :prompt))]
      (when-not (string? prompt)
        (throw (ex-info "input :prompt must be a string"
                        {:input input
                         :prompt prompt})))
      (if data
        (str prompt "\n\nINPUT EDN:\n" (pr-str data))
        prompt))
    :else (pr-str input)))

(defn- run-request->args
  [request]
  (when-not (map? request)
    (throw (ex-info "run expects a request map"
                    {:value request
                     :supported '(run {:runner ... :workflow ... :input ...})})))
  (k/reject-unknown! "run request" run-request-keys request)
  (doseq [k [:runner :workflow]]
    (when-not (contains? request k)
      (throw (ex-info (str "run request requires " k) {:request request}))))
  (when (and (contains? request :options) (not (map? (:options request))))
    (throw (ex-info "run request :options must be a map"
                    {:options (:options request)})))
  [(:runner request)
   (:workflow request)
   (input->text (:input request))
   (or (:options request) {})])

(defn- run*
  [runner workflow input opts]
  (let [input (input->text input)]
    (obs/with-span opts
      (fn [phase span-id parent-span-id attrs]
        (workflow-event phase span-id parent-span-id workflow attrs))
      (fn [span-id]
        (run-node runner workflow input
                  (cond-> opts
                    span-id (assoc :karcarthy/parent-span-id span-id)))))))

(defn run
  "Interpret workflow data through a runner and return a result map.

    (run {:runner runner
          :workflow workflow
          :input {:question \"...\"}
          :options {:observe observe-fn}})

  `:input` is the initial task/user message/state. A string is passed as the
  prompt. A map with `:prompt` uses that string as the prompt and appends the
  remaining keys as EDN. Other EDN input is rendered with `pr-str` before
  entering the text-threaded workflow interpreter.

  `:options` keys read by the interpreter:
    :observe      fn called with each observation event map
    :edn-retries  how many times a node re-asks the model when its EDN reply
                  fails to parse or validate (default 1; 0 fails fast)
  Remaining options are passed through to the runner."
  [request]
  (let [[runner workflow input opts] (run-request->args request)]
    (run* runner workflow input opts)))

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
                 (or (not (contains? x :call?))
                     (boolean? (:call? x))))

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
;; Dynamic workflows (experimental)
;;
;; The public vars in this section are tagged ^:experimental: the op protocol
;; and prompt format may change between releases.
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
      (k/run-agent runner agent (input->text input) opts))

    (contains? op :workflow)
    (let [target   (:workflow op)
          workflow (if (or (string? target) (keyword? target))
                     (lookup-workflow state target)
                     target)]
      (run* runner (refs->workflow state workflow) input opts))

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
          results  (bounded-pmap (:max-concurrency op)
                                 (fn [[idx input]]
                                   (safe-run runner workflow (input->text input)
                                             (descend opts :spawn idx)))
                                 indexed)]
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
    "  finish the run; :text is the final answer to the task."
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

(defmethod node? :dynamic
  [{:keys [agent max-steps]}]
  (and (k/agent? agent)
       (or (nil? max-steps)
           (and (integer? max-steps) (pos? max-steps)))))

(defmethod run-node :dynamic
  [runner {:keys [agent max-steps]} input opts]
  (let [st (or (:state opts) (state))]
    (loop [step 1, last-result nil, failures 0]
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
                           failures)))
                (let [{:keys [op result]} outcome]
                  (if (= :complete (:op op))
                    result
                    (recur (inc step) result 0)))))))))))

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

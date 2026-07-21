(ns karcarthy.run
  "Agent execution, the model/Tool loop, limits, and events."
  (:refer-clojure :exclude [run!])
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [karcarthy.agent :refer [agent? normalize-model]]
            [karcarthy.schema :as schema
             :refer [fail! throwable->failure]]
            [karcarthy.session :as session]
            [karcarthy.tool :refer [hosted-tool? make-tool tool?]])
  (:import [java.util UUID]
           [java.util.concurrent Callable ExecutionException Executors Future
            Semaphore TimeUnit TimeoutException]))

(declare await-future! run-agent! run!)

(def ^:dynamic ^:no-doc *run*
  "The active run context. `run!` binds this value, including across futures."
  nil)

(defn ^:no-doc current-run-context
  "Return the internal context for the Agent or Tool currently running."
  []
  (or *run*
      (fail! :run :context
             "This operation is only available while an Agent is running")))

(defn- eval-tool [model tools agents]
  ((requiring-resolve 'karcarthy.eval/eval-tool) model tools agents))

(def ^:private agent-call-schema
  {:type "object"
   :properties
   {"input" {:description "The complete input value passed to this Agent."}}
   :required ["input"]
   :additionalProperties false})

(defn- object-schema? [schema]
  (contains? #{"object" :object}
             (or (:type schema) (get schema "type"))))

(defn- agent-as-tool
  [agent]
  (when-not (agent? agent)
    (fail! :schema :configuration
           "Agent :agents must contain Agent values"
           {:value agent}))
  (let [schema (schema/json-schema (:input-schema agent))
        structured-input? (object-schema? schema)
        input-schema (if structured-input?
                       (:input-schema agent)
                       agent-call-schema)]
    (make-tool
     {:name (:name agent)
      :description
      (str (or (:description agent)
               (str "Ask " (:name agent) " to complete a task."))
           " This Agent starts without the parent conversation and receives "
           (if structured-input?
             "the Tool input object directly."
             "only the value in the `input` field.")
           " Its final output returns as the Tool result.")
      :input-schema input-schema
      :output-schema (:output-schema agent)
      :needs-approval :never}
     nil
     nil
     (fn [ctx input]
       (run-agent! ctx agent
                   (if structured-input? input (:input input))
                   {})))))

;; ---------------------------------------------------------------------------
;; Run context, limits, and observation
;; ---------------------------------------------------------------------------

(def default-limits
  {:model-calls 100
   :input-tokens Long/MAX_VALUE
   :output-tokens Long/MAX_VALUE
   :depth 8
   :concurrency 16
   :evals 20
   :deadline-ms nil})

(def ^:private run-option-keys
  #{:context :session :limits :on-event :approval :cancel
    :model-transports})

(def ^:private agent-call-option-keys #{:context :limits})

(defn- validate-limits!
  [limits]
  (when-not (map? limits)
    (fail! :schema :configuration "Run limits must be a map"
           {:value limits}))
  (schema/reject-unknown! "Limits" (set (keys default-limits)) limits)
  (doseq [resource [:model-calls :input-tokens :output-tokens
                    :depth :evals]]
    (let [value (get limits resource)]
      (when-not (and (integer? value) (not (neg? value)))
        (fail! :schema :configuration
               (str (name resource) " must be a non-negative integer")
               {:resource resource :value value}))))
  (when-not (and (integer? (:concurrency limits))
                 (pos? (:concurrency limits)))
    (fail! :schema :configuration
           ":concurrency must be a positive integer"
           {:value (:concurrency limits)}))
  (when-let [deadline-ms (:deadline-ms limits)]
    (when-not (and (integer? deadline-ms) (not (neg? deadline-ms)))
      (fail! :schema :configuration
             ":deadline-ms must be nil or a non-negative integer"
             {:value deadline-ms})))
  limits)

(defn- id [prefix]
  (str prefix (UUID/randomUUID)))

(defn context
  "Return the local, non-model-visible context for the current Agent call."
  ([]
   (context (current-run-context)))
  ([run-context]
   (:context run-context)))

(defn- run-context
  [ctx]
  {:run-id (:run-id ctx)
   :agent-id (:agent-id ctx)
   :parent-id (:parent-id ctx)
   :depth (:depth ctx)
   :context (:context ctx)
   :limits (:limits ctx)
   :usage @(:usage ctx)
   :agent (:agent ctx)})

(defn emit!
  "Record one observation event and notify the run observer."
  ([event]
   (emit! (current-run-context) event))
  ([ctx event]
   (let [event (merge {:karcarthy/type :event
                       :time-ms (System/currentTimeMillis)
                       :run-id (:run-id ctx)}
                      (select-keys ctx [:agent-id :parent-id :depth])
                      event)]
     (when-not (contains? #{:model/text-delta :model/tool-call-delta
                            :model/stream-event}
                          (:type event))
       (swap! (:events ctx) conj event)
       (doseq [collector (:event-collectors ctx)]
         (swap! collector conj event)))
     (when-let [on-event (:on-event ctx)]
       (try (on-event event) (catch Throwable _ nil)))
     event)))

(defn events
  "Return the events recorded by a Run."
  [run]
  (let [v (:events run)]
    (if (instance? clojure.lang.IDeref v) @v (vec v))))

(defn output
  "Return the output of a completed Run, or throw with the Run as data."
  [run]
  (if (= :completed (:status run))
    (:output run)
    (throw (ex-info (or (get-in run [:error :message])
                        "Agent Run did not complete")
                    {:run run}))))

(defn- cancellation-requested? [cancel]
  (cond
    (nil? cancel) false
    (fn? cancel) (boolean (cancel))
    (instance? clojure.lang.IDeref cancel) (boolean @cancel)
    :else (boolean cancel)))

(defn ^:no-doc check-run!
  [rt]
  (when (cancellation-requested? (:cancel rt))
    (fail! :cancellation :run "Run was cancelled"))
  (when-let [deadline (:deadline-ns rt)]
    (when (>= (System/nanoTime) deadline)
      (fail! :deadline :run "Run deadline was exceeded")))
  rt)

(defn- limit-value [rt k]
  (get (:limits rt) k Long/MAX_VALUE))

(defn ^:no-doc consume!
  "Atomically consume a shared run resource budget."
  [rt k n]
  (check-run! rt)
  (loop []
    (let [before @(:usage rt)
          after (update before k (fnil + 0) n)
          limit (limit-value rt k)]
      (when (and limit (> (get after k) limit))
        (fail! :budget :run
               (str "Run limit exceeded: " (name k))
               {:resource k :used (get before k) :requested n :limit limit}))
      (if (compare-and-set! (:usage rt) before after)
        (get after k)
        (recur)))))

(defn- narrower-limits [parent child]
  (merge-with (fn [a b]
                (cond
                  (nil? a) b
                  (nil? b) a
                  :else (min a b)))
              parent (or child {})))

(defn- run-guardrails!
  [ctx phase guards value]
  (doseq [guard (or guards [])]
    (let [result (guard (run-context ctx) value)]
      (when (or (false? result)
                (and (map? result) (false? (:ok? result))))
        (fail! :guardrail phase
               (str "Guardrail rejected " (name phase))
               {:result result :value value}))))
  value)

;; ---------------------------------------------------------------------------
;; Model transport
;; ---------------------------------------------------------------------------

(defn mock-model
  "Deterministic in-process model transport for tests."
  [respond]
  (when-not (fn? respond)
    (throw (IllegalArgumentException. "mock-model requires a function")))
  {:karcarthy/type :model-transport
   :complete respond})

(defn- resolve-value [value ctx]
  (if (fn? value) (value (run-context ctx)) value))

(defn- resolve-transport [rt model]
  (let [configured (:transport model)
        transport
        (if (keyword? configured)
          (or (get (:model-transports rt) configured)
              (when (= :responses configured)
                (deref (requiring-resolve
                        'karcarthy.model.responses/transport))))
          configured)]
    (or transport
        (fail! :model :configuration
               "No model transport is configured"
               {:transport configured :model model}))))

(defn- call-transport [transport request emit-delta!]
  (cond
    (and (map? transport)
         (not= false (get-in request [:model :stream]))
         (fn? (:stream transport)))
    ((:stream transport) request emit-delta!)

    (and (map? transport) (fn? (:complete transport)))
    ((:complete transport) request)

    (ifn? transport) (transport request)

    :else
    (fail! :model :configuration "Invalid model transport"
           {:transport transport})))

(defn- record-model-delta! [rt delta]
  (check-run! rt)
  (case (:type delta)
    :text-delta
    (emit! rt {:type :model/text-delta :delta (:delta delta)})

    :tool-call-delta
    (emit! rt (assoc (dissoc delta :type) :type :model/tool-call-delta))

    (emit! rt {:type :model/stream-event :event delta})))

(defn- normalize-model-response [response]
  (cond
    (string? response) {:type :final :output response}
    (not (map? response)) {:type :final :output response}
    (= :final (:type response)) response
    (= :tool-calls (:type response)) response
    (seq (:tool-calls response))
    (assoc response :type :tool-calls :calls (:tool-calls response))
    (contains? response :output) (assoc response :type :final)
    :else (fail! :model :response "Model transport returned an invalid response"
                 {:response response})))

(defn model!
  "Call the active model transport with one normalized request."
  ([request]
   (model! (current-run-context) request))
  ([rt request]
   (check-run! rt)
   (consume! rt :model-calls 1)
   (let [model (normalize-model (resolve-value (:model request) rt))
         transport (resolve-transport rt model)
         request (assoc request :model model)
         started (System/nanoTime)]
     (emit! rt {:type :model/requested
                :model (select-keys model [:provider :transport :id])})
     (try
       (let [child (.submit (:executor rt)
                            ^Callable
                            (reify Callable
                              (call [_]
                                (binding [*run* rt]
                                  (call-transport
                                   transport request
                                   #(record-model-delta! rt %))))))
             response (normalize-model-response (await-future! rt child))
             usage (:usage response)]
         (when-let [n (or (:input-tokens usage) (:input_tokens usage))]
           (consume! rt :input-tokens n))
         (when-let [n (or (:output-tokens usage) (:output_tokens usage))]
           (consume! rt :output-tokens n))
         (emit! rt {:type :model/completed
                    :duration-ms (/ (double (- (System/nanoTime) started)) 1000000.0)
                    :response-type (:type response)
                    :usage usage})
         response)
       (catch Throwable t
         (emit! rt {:type :model/failed
                    :duration-ms (/ (double (- (System/nanoTime) started)) 1000000.0)
                    :error (throwable->failure t)})
         (if (= :failure (:karcarthy/type (ex-data t)))
           (throw t)
           (fail! :model :request (or (ex-message t) (str t)) nil t)))))))

;; ---------------------------------------------------------------------------
;; Tool execution and the native model loop
;; ---------------------------------------------------------------------------

(defn- tool-descriptor [tool]
  (cond
    (tool? tool)
    (let [schema (schema/json-schema (:input-schema tool))]
      (when-not schema
        (fail! :tool :configuration
               "Tool :input-schema must be expressible as JSON Schema"
               {:tool (:name tool)
                :input-schema (:input-schema tool)}))
      {:kind :function
       :name (:name tool)
       :description (:description tool)
       :parameters schema})

    (hosted-tool? tool)
    {:kind :hosted
     :transport (:transport tool)
     :spec (:spec tool)}

    :else
    (fail! :tool :configuration "Agent contains an invalid Tool"
           {:value tool})))

(defn- approval-policy
  [policy ctx input]
  (if (fn? policy) (policy (run-context ctx) input) policy))

(defn- request-approval!
  [rt tool input policy]
  (emit! rt {:type :tool/approval-requested
             :tool (:name tool) :input input :policy policy})
  (let [allowed?
        (if-let [handler (:approval rt)]
          (boolean (handler (assoc (run-context rt)
                                   :tool tool
                                   :input input)))
          false)]
    (emit! rt {:type :tool/approval-resolved
               :tool (:name tool) :approved? allowed? :policy policy})
    allowed?))

(defn- approved?
  [rt tool input]
  (let [policy (approval-policy (:needs-approval tool :never)
                                rt input)]
    (cond
      (not (contains? #{true :always :once} policy)) true
      (not= :once policy) (request-approval! rt tool input policy)
      :else
      (let [approvals (:approvals rt)
            approval-key [:tool (:name tool)]]
        (locking approvals
          (if (contains? @approvals approval-key)
            (do
              (emit! rt {:type :tool/approval-reused
                         :tool (:name tool) :policy policy})
              true)
            (let [allowed? (request-approval! rt tool input policy)]
              (when allowed? (swap! approvals conj approval-key))
              allowed?)))))))

(defn- execute-tool-body! [rt tool input]
  (binding [*run* rt]
    ((:execute tool) rt input)))

(defn- run-tool!
  [rt tool call]
  (when-not (tool? tool)
    (fail! :tool :configuration "Agent contains an invalid Tool"
           {:value tool}))
  (let [input (:input call)
        call-id (or (:id call) (id "tool_"))]
    (schema/check! :tool-input (:input-schema tool) input)
    (run-guardrails! rt :tool-input (:input-guardrails tool) input)
    (when-not (approved? rt tool input)
      (fail! :approval :tool "Tool approval was denied"
             {:tool (:name tool) :input input}))
    (emit! rt {:type :tool/started :tool (:name tool)
               :tool-call-id call-id :input input})
    (let [started (System/nanoTime)]
      (try
        (let [output (execute-tool-body! rt tool input)
              _ (schema/check! :tool-output (:output-schema tool) output)
              _ (run-guardrails! rt :tool-output
                                 (:output-guardrails tool) output)
              model-output (if-let [project (:to-model-output tool)]
                             (project output)
                             output)
              result {:id call-id
                      :name (:name tool)
                      :output output
                      :model-output model-output}]
          (emit! rt {:type :tool/completed :tool (:name tool)
                     :tool-call-id call-id
                     :duration-ms (/ (double (- (System/nanoTime) started)) 1000000.0)
                     :output output})
          result)
        (catch Throwable t
          (let [failure (throwable->failure t)
                recoverable? (contains? #{:execution :mcp :tool}
                                        (:kind failure))
                error (cond-> failure
                        recoverable? (assoc :recoverable? true))]
            (emit! rt {:type :tool/failed :tool (:name tool)
                       :tool-call-id call-id
                       :duration-ms (/ (double (- (System/nanoTime) started))
                                      1000000.0)
                       :error error})
            (if recoverable?
              {:id call-id
               :name (:name tool)
               :output nil
               :model-output {:error (:message error)}
               :is-error true
               :error error}
              (throw t))))))))

(defn- submit-limited! [rt f]
  (let [^Semaphore semaphore (:semaphore rt)]
    (when-not (.tryAcquire semaphore)
      (fail! :budget :concurrency "Run concurrency limit was reached"
             {:limit (get-in rt [:limits :concurrency])}))
    (try
      (.submit (:executor rt)
               ^Callable
               (reify Callable
                 (call [_]
                   (try (f) (finally (.release semaphore))))))
      (catch Throwable t
        (.release semaphore)
        (throw t)))))

(defn- await-future!
  [rt ^Future child]
  (try
    (loop []
      (check-run! rt)
      (let [result (try
                     {:value (.get child 25 TimeUnit/MILLISECONDS)}
                     (catch TimeoutException _
                       {:timeout? true})
                     (catch ExecutionException error
                       (throw (or (.getCause error) error))))]
        (if (:timeout? result)
          (recur)
          (:value result))))
    (catch InterruptedException error
      (.cancel child true)
      (.interrupt (Thread/currentThread))
      (fail! :cancellation :run "Run thread was interrupted" nil error))
    (catch Throwable error
      (when-not (.isDone child)
        (.cancel child true))
      (throw error))))

(defn- await-futures!
  [rt children]
  (try
    (loop [pending (vec (map-indexed vector children))
           results (vec (repeat (count children) nil))]
      (check-run! rt)
      (if (empty? pending)
        results
        (if-let [[index child]
                 (first (filter (fn [[_ ^Future child]] (.isDone child))
                                pending))]
          (recur (filterv #(not= index (first %)) pending)
                 (assoc results index (await-future! rt child)))
          (do
            (Thread/sleep 5)
            (recur pending results)))))
    (catch InterruptedException error
      (doseq [^Future child children]
        (when-not (.isDone child)
          (.cancel child true)))
      (.interrupt (Thread/currentThread))
      (fail! :cancellation :run "Run thread was interrupted" nil error))
    (catch Throwable error
      (doseq [^Future child children]
        (when-not (.isDone child)
          (.cancel child true)))
      (throw error))))

(defn- maybe-json-output [output schema]
  (if (and schema (string? output)
           (re-find #"^\s*[\[{]" output))
    (try (json/read-str output :key-fn keyword)
         (catch Throwable _ output))
    output))

(def ^:private message-roles #{:system :user :assistant :tool})

(defn- valid-message? [message]
  (and (map? message)
       (contains? message-roles (:role message))))

(defn- session-items! [active-session]
  (if-not active-session
    []
    (let [items (try
                  (session/get-items active-session)
                  (catch Throwable error
                    (fail! :session :read
                           (or (ex-message error) "Session read failed")
                           nil error)))]
      (when-not (sequential? items)
        (fail! :session :read "Session items must be sequential"
               {:value items}))
      (doseq [item items]
        (when-not (valid-message? item)
          (fail! :session :read "Session contains an invalid message"
                 {:value item})))
      (vec items))))

(defn- append-session-items! [rt items]
  (let [items (vec items)]
    (when (and (:session rt) (seq items))
      (try
        (session/add-items! (:session rt) items)
        (catch Throwable error
          (fail! :session :write
                 (or (ex-message error) "Session write failed")
                 nil error)))
      (emit! rt {:type :session/updated
                 :session-id (session/session-id (:session rt))
                 :items (count items)})))
  nil)

(defn- instructions! [rt value]
  (let [resolved
        (try
          (resolve-value value rt)
          (catch clojure.lang.ExceptionInfo error
            (if (= :failure (:karcarthy/type (ex-data error)))
              (throw error)
              (fail! :instructions :resolve
                     (or (ex-message error) "Instructions resolution failed")
                     nil error)))
          (catch Throwable error
            (fail! :instructions :resolve
                   (or (ex-message error) "Instructions resolution failed")
                   nil error)))]
    (when-not (string? resolved)
      (fail! :instructions :resolve
             "Agent :instructions must resolve to a string"
             {:value resolved}))
    resolved))

(defn- unique-tools! [tools]
  (let [duplicates (->> tools
                        (filter tool?)
                        (map :name)
                        frequencies
                        (keep (fn [[name n]] (when (> n 1) name)))
                        sort
                        vec)]
    (when (seq duplicates)
      (fail! :schema :configuration
             "Agent contains duplicate Tool or Agent names"
             {:names duplicates}))
    tools))

(defn- model-loop!
  [rt agent input]
  (let [max-turns (or (:max-turns agent) 20)
        model (normalize-model (resolve-value (:model agent) rt))
        tools-value (:tools agent)
        direct-tools (vec (or (resolve-value tools-value rt) []))
        agents-value (:agents agent)
        available-agents (vec (or (resolve-value agents-value rt) []))
        _ (doseq [tool direct-tools]
            (when-not (or (tool? tool) (hosted-tool? tool))
              (fail! :tool :configuration
                     "Agent :tools must contain Tool values"
                     {:value tool})))
        agent-tools (mapv agent-as-tool available-agents)
        tools (unique-tools!
               (conj (into direct-tools agent-tools)
                     (eval-tool model direct-tools available-agents)))
        tool-map (into {} (comp (filter tool?) (map (juxt :name identity))) tools)
        instructions (instructions! rt (:instructions agent))
        prior (session-items! (:session rt))
        user-message {:role :user :content input}
        messages (conj prior user-message)]
    (loop [turn 1
           messages messages
           pending messages
           provider-state nil
           unpersisted [user-message]]
      (check-run! rt)
      (when (> turn max-turns)
        (fail! :budget :model-loop "Agent exceeded :max-turns"
               {:agent (:name agent) :max-turns max-turns}))
      (let [request {:agent agent
                     :model model
                     :instructions instructions
                     :messages pending
                     :provider-state provider-state
                     :tools (mapv tool-descriptor tools)
                     :output-schema (schema/json-schema
                                     (:output-schema agent))
                     :turn turn}
            response (model! rt request)]
        (case (:type response)
          :final
          (let [output (maybe-json-output (:output response)
                                          (:output-schema agent))]
            (reset! (:pending-session-items rt)
                    (conj (vec unpersisted)
                          {:role :assistant :content output}))
            output)

          :tool-calls
          (let [calls (vec (:calls response))
                jobs (mapv (fn [call]
                             (let [tool (get tool-map (:name call))]
                               (when-not tool
                                 (fail! :tool :not-found
                                        (str "Unknown tool: " (:name call))
                                        {:known (vec (keys tool-map))}))
                               (submit-limited! rt #(run-tool! rt tool call))))
                           calls)
                results (await-futures! rt jobs)
                assistant-message {:role :assistant :tool-calls calls}
                result-messages
                (mapv (fn [{:keys [id name model-output is-error]}]
                        (cond-> {:role :tool
                                 :tool-call-id id
                                 :name name
                                 :content model-output}
                          is-error (assoc :is-error true)))
                      results)
                new-items (into [assistant-message] result-messages)]
            (append-session-items! rt (into (vec unpersisted) new-items))
            (recur (inc turn)
                   (into messages new-items)
                   result-messages
                   (:provider-state response)
                   []))

          (fail! :model :response "Unsupported model response type"
                 {:response response}))))))

;; ---------------------------------------------------------------------------
;; Agent execution and composition
;; ---------------------------------------------------------------------------

(defn- run-agent!
  [parent-rt agent input options]
  (when-not (agent? agent)
    (fail! :schema :agent "run! requires an Agent" {:value agent}))
  (let [options (or options {})]
    (schema/reject-unknown! "Participating run options"
                              agent-call-option-keys options))
  (let [depth (inc (:depth parent-rt))
        limits (validate-limits!
                (narrower-limits (:limits parent-rt)
                                 (merge (:limits agent)
                                        (:limits options))))]
    (when (> depth (:depth limits))
      (fail! :budget :depth "Agent depth limit was reached"
             {:depth depth :limit (:depth limits)}))
    (let [local-deadline-ns
          (when-let [ms (:deadline-ms limits)]
            (+ (System/nanoTime) (* 1000000 (long ms))))
          deadline-ns
          (let [parent-deadline (:deadline-ns parent-rt)]
            (cond
              (nil? parent-deadline) local-deadline-ns
              (nil? local-deadline-ns) parent-deadline
              :else (min parent-deadline local-deadline-ns)))
          active-session (when (zero? depth) (:initial-session parent-rt))
          rt (assoc parent-rt
                    :agent-id (id "agent_")
                    :parent-id (:agent-id parent-rt)
                    :depth depth
                    :agent agent
                    :agent-input input
                    :context (if (contains? options :context)
                               (:context options)
                               (:context parent-rt))
                    :session active-session
                    :pending-session-items (atom nil)
                    :limits limits
                    :deadline-ns deadline-ns)
          started (System/nanoTime)]
      (schema/check! :context (:context-schema agent) (:context rt))
      (schema/check! :agent-input (:input-schema agent) input)
      (run-guardrails! rt :agent-input (:input-guardrails agent) input)
      (emit! rt {:type :agent/started :agent (:name agent) :input input})
      (try
        (let [output (binding [*run* rt]
                       (model-loop! rt agent input))
              output (maybe-json-output output (:output-schema agent))]
          (check-run! rt)
          (schema/check! :agent-output (:output-schema agent) output)
          (run-guardrails! rt :agent-output (:output-guardrails agent) output)
          (when-let [items @(:pending-session-items rt)]
            (append-session-items! rt items))
          (emit! rt {:type :agent/completed :agent (:name agent)
                     :duration-ms (/ (double (- (System/nanoTime) started)) 1000000.0)
                     :output output})
          output)
        (catch Throwable t
          (emit! rt {:type :agent/failed :agent (:name agent)
                     :duration-ms (/ (double (- (System/nanoTime) started)) 1000000.0)
                     :error (throwable->failure t)})
          (if (= :failure (:karcarthy/type (ex-data t)))
            (throw t)
            (fail! :execution :agent (or (ex-message t) (str t))
                   {:agent (:name agent)} t)))))))

(defn- run-result
  [rt agent input started events output error]
  {:karcarthy/type :run
   :id (:run-id rt)
   :status (cond
             (nil? error) :completed
             (= :cancellation (:kind error)) :cancelled
             :else :failed)
   :agent (:name agent)
   :input input
   :output output
   :usage (assoc @(:usage rt) :duration-ms
                 (/ (double (- (System/nanoTime) started)) 1000000.0))
   :events events
   :error error})

(defn- run-agent-call!
  [rt agent input options]
  (let [started (System/nanoTime)
        call-events (atom [])
        rt (update rt :event-collectors (fnil conj []) call-events)]
    (try
      (let [output (run-agent! rt agent input options)]
        (run-result rt agent input started @call-events output nil))
      (catch Throwable t
        (run-result rt agent input started @call-events nil
                    (throwable->failure t))))))

(defn run!
  "Run an Agent and return a Run map.

  The first call establishes a run. Calls made during its dynamic extent join
  that run and therefore share its id, limits, usage, deadline, cancellation,
  approvals, events, executor, and context. Each Agent starts a fresh model
  conversation unless it is the first call with a Session."
  ([agent input]
   (run! agent input {}))
  ([agent input options]
   (if *run*
     (let [rt *run*
           started (System/nanoTime)]
       (try
         (await-future! rt (submit-limited!
                            rt #(run-agent-call! rt agent input
                                                 (or options {}))))
         (catch Throwable t
           (run-result rt agent input started [] nil
                       (throwable->failure t)))))
     (let [options (or options {})
           _ (schema/reject-unknown! "Run options" run-option-keys options)
           _ (when (and (some? (:session options))
                        (not (session/session? (:session options))))
               (fail! :schema :session
                      "Run :session must implement karcarthy.session/Session"
                      {:value (:session options)}))
           _ (when-not (agent? agent)
               (fail! :schema :agent "run! requires an Agent" {:value agent}))
           limits (validate-limits! (merge default-limits (:limits options)))
           executor (Executors/newVirtualThreadPerTaskExecutor)
           events* (atom [])
           usage* (atom {:model-calls 0
                         :input-tokens 0
                         :output-tokens 0
                         :evals 0})
           run-id (id "run_")
           started (System/nanoTime)
           deadline-ns (when-let [ms (:deadline-ms limits)]
                         (+ started (* 1000000 (long ms))))
           rt {:karcarthy/type :run-context
               :run-id run-id
               :agent-id nil
               :parent-id nil
               :depth -1
               :agent nil
               :context (:context options)
               :limits limits
               :usage usage*
               :events events*
               :initial-session (:session options)
               :on-event (:on-event options)
               :approval (:approval options)
               :approvals (atom #{})
               :cancel (:cancel options)
               :model-transports (:model-transports options)
               :executor executor
               :semaphore (Semaphore. (int (:concurrency limits)) true)
               :deadline-ns deadline-ns
               :eval-parent-namespace (:definition-ns agent)
               :eval-namespace
               (symbol
                (str 'karcarthy.eval
                     ".run_" (str/replace run-id "-" "_")))
               :eval-counter (atom 0)}]
       (emit! rt {:type :run/started :agent (:name agent) :input input})
       (try
         (let [call #(binding [*run* rt]
                       (run-agent-call! rt agent input {}))
               result (if-let [active-session (:initial-session rt)]
                        (locking active-session (call))
                        (call))
               event-type (case (:status result)
                            :completed :run/completed
                            :cancelled :run/cancelled
                            :run/failed)]
           (emit! rt (cond-> {:type event-type :agent (:name agent)}
                       (= :completed (:status result))
                       (assoc :output (:output result) :usage @usage*)
                       (not= :completed (:status result))
                       (assoc :error (:error result))))
           (assoc result
                  :usage (assoc @usage* :duration-ms
                                (/ (double (- (System/nanoTime) started))
                                   1000000.0))
                  :events @events*))
         (finally
           (.shutdownNow executor)))))))

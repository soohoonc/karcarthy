(ns karcarthy.run.context
  "Shared Run state, limits, events, and concurrent work."
  (:require [karcarthy.schema :as schema :refer [fail!]]
            [karcarthy.session :as session])
  (:import [java.util UUID]
           [java.util.concurrent Callable ExecutionException Future Semaphore
            TimeUnit TimeoutException]))

(def ^:dynamic ^:no-doc *run*
  "The active run context. `run!` binds this value, including across futures."
  nil)

(defn ^:no-doc current-run-context
  "Return the internal context for the Agent or Tool currently running."
  []
  (or *run*
      (fail! :run :context
             "This operation is only available while an Agent is running")))

(def default-limits
  {:model-calls 100
   :input-tokens Long/MAX_VALUE
   :output-tokens Long/MAX_VALUE
   :depth 8
   :concurrency 16
   :evals 20
   :deadline-ms nil})

(def ^:no-doc run-option-keys
  #{:context :session :limits :on-event :approval :cancel
    :model-transports})

(def ^:no-doc agent-call-option-keys #{:context :limits})

(defn ^:no-doc validate-limits!
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

(defn ^:no-doc id [prefix]
  (str prefix (UUID/randomUUID)))

(defn context
  "Return the local, non-model-visible context for the current Agent call."
  ([]
   (context (current-run-context)))
  ([run-context]
   (:context run-context)))

(defn ^:no-doc run-context
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
   (let [event (merge event
                      {:karcarthy/type :event
                       :time-ms (System/currentTimeMillis)
                       :run-id (:run-id ctx)}
                      (select-keys ctx [:agent-id :parent-id :depth]))]
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
  (let [value (:events run)]
    (if (instance? clojure.lang.IDeref value) @value (vec value))))

(defn output
  "Return the output of a completed Run, or throw with the Run as data."
  [run]
  (if (= :completed (:status run))
    (:output run)
    (throw (ex-info (or (get-in run [:error :message])
                        "Agent Run did not complete")
                    {:run run}))))

(def ^:private message-roles #{:system :user :assistant :tool})

(defn- valid-message? [message]
  (and (map? message)
       (contains? message-roles (:role message))))

(defn ^:no-doc session-items!
  [active-session]
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

(defn ^:no-doc append-items!
  [run-context items]
  (let [items (vec items)]
    (when (and (:session run-context) (seq items))
      (try
        (session/add-items! (:session run-context) items)
        (catch Throwable error
          (fail! :session :write
                 (or (ex-message error) "Session write failed")
                 nil error)))
      (emit! run-context
             {:type :session/updated
              :session-id (session/session-id (:session run-context))
              :items (count items)})))
  nil)

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

(defn- limit-value [rt key]
  (get (:limits rt) key Long/MAX_VALUE))

(defn ^:no-doc consume!
  "Atomically consume a shared run resource budget."
  [rt key amount]
  (check-run! rt)
  (loop []
    (let [before @(:usage rt)
          after (update before key (fnil + 0) amount)
          limit (limit-value rt key)]
      (when (and limit (> (get after key) limit))
        (fail! :budget :run
               (str "Run limit exceeded: " (name key))
               {:resource key
                :used (get before key)
                :requested amount
                :limit limit}))
      (if (compare-and-set! (:usage rt) before after)
        (get after key)
        (recur)))))

(defn ^:no-doc narrower-limits [parent child]
  (merge-with (fn [a b]
                (cond
                  (nil? a) b
                  (nil? b) a
                  :else (min a b)))
              parent (or child {})))

(defn ^:no-doc run-guardrails!
  [ctx phase guards value]
  (doseq [guard (or guards [])]
    (let [result (guard (run-context ctx) value)]
      (when (or (false? result)
                (and (map? result) (false? (:ok? result))))
        (fail! :guardrail phase
               (str "Guardrail rejected " (name phase))
               {:result result :value value}))))
  value)

(defn ^:no-doc submit-limited! [rt f]
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
      (catch Throwable error
        (.release semaphore)
        (throw error)))))

(defn ^:no-doc await-future!
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

(defn ^:no-doc await-futures!
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

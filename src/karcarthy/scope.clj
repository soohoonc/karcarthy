(ns karcarthy.scope
  "Execution scope shared by every node in one workflow run."
  (:refer-clojure :exclude [await])
  (:import [java.util.concurrent CancellationException ExecutionException Future
            Semaphore TimeUnit TimeoutException]))

(def ^:private poll-ms 100)
(def ^:private timed-out (Object.))

(defn create
  "Create a run scope from public options.

  :max-concurrency  maximum leaf-agent calls in flight across the whole run
  :run-timeout-ms   deadline for the whole run
  :cancel?          optional zero-argument cancellation predicate"
  [{:keys [max-concurrency run-timeout-ms cancel?]}]
  (let [max-concurrency (or max-concurrency 16)]
    (when-not (and (integer? max-concurrency) (pos? max-concurrency))
      (throw (ex-info ":max-concurrency must be a positive integer"
                      {:value max-concurrency})))
    (when (and (some? run-timeout-ms)
               (not (and (integer? run-timeout-ms) (pos? run-timeout-ms))))
      (throw (ex-info ":run-timeout-ms must be a positive integer"
                      {:value run-timeout-ms})))
    (when (and (some? cancel?) (not (fn? cancel?)))
      (throw (ex-info ":cancel? must be a zero-argument function"
                      {:value cancel?})))
    {:max-concurrency max-concurrency
     :semaphore       (Semaphore. (int max-concurrency) true)
     :deadline-ns     (when run-timeout-ms
                        (+ (System/nanoTime) (* 1000000 (long run-timeout-ms))))
     :cancel?         cancel?}))

(defn limited?
  "True when the scope has a deadline or external cancellation predicate."
  [scope]
  (boolean (or (:deadline-ns scope) (:cancel? scope))))

(defn- fail!
  ([kind message]
   (throw (ex-info message {::error kind})))
  ([kind message cause]
   (throw (ex-info message {::error kind} cause))))

(defn- remaining-ms
  [scope]
  (when-let [deadline (:deadline-ns scope)]
    (let [remaining (- deadline (System/nanoTime))]
      (if (pos? remaining)
        (long (Math/ceil (/ (double remaining) 1000000.0)))
        0))))

(defn- check!
  [scope]
  (when-let [cancel? (:cancel? scope)]
    (let [cancelled? (try
                       (boolean (cancel?))
                       (catch Throwable t
                         (fail! :cancel-check-failed
                                "workflow cancellation check failed" t)))]
      (when cancelled?
        (fail! :cancelled "workflow run was cancelled"))))
  (when (zero? (or (remaining-ms scope) 1))
    (fail! :deadline-exceeded "workflow run deadline exceeded"))
  scope)

(defn- wait-slice-ms [scope]
  (if-let [remaining (remaining-ms scope)]
    (max 1 (min poll-ms remaining))
    poll-ms))

(defn- poll! [scope attempt]
  (loop []
    (check! scope)
    (let [result (attempt (wait-slice-ms scope))]
      (if (identical? timed-out result)
        (recur)
        result))))

(defn with-permit
  "Run `f` while holding one run-wide leaf-call permit."
  [scope f]
  (if-not scope
    (f)
    (let [^Semaphore semaphore (:semaphore scope)]
      (poll! scope
             #(if (.tryAcquire semaphore % TimeUnit/MILLISECONDS)
                true
                timed-out))
      (try
        (check! scope)
        (f)
        (finally (.release semaphore))))))

(defn await
  "Wait for a future while polling the run deadline and cancellation predicate."
  [scope ^Future future]
  (if-not (limited? scope)
    (.get future)
    (poll! scope
           (fn [wait-ms]
             (try
               (.get future wait-ms TimeUnit/MILLISECONDS)
               (catch TimeoutException _ timed-out)
               (catch CancellationException _
                 (fail! :cancelled "workflow run was cancelled"))
               (catch ExecutionException e
                 (throw (or (.getCause e) e))))))))

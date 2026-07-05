(ns karcarthy.observe
  "Internal helpers for emitting observation events.

  When a run is given an `:observe` callback in its options, `karcarthy.core`
  and `karcarthy.orchestrate` emit OTel-shaped event maps through these
  helpers. Observer errors are swallowed so observation can never fail a run."
  (:import [java.util UUID]))

(defn span-id
  "Return a fresh random span id (a UUID string)."
  []
  (str (UUID/randomUUID)))

(defn now-ms
  "Current wall-clock time in epoch milliseconds."
  []
  (System/currentTimeMillis))

(defn duration-ms
  "Milliseconds elapsed since `started-ns` (a `System/nanoTime` value)."
  [started-ns]
  (/ (double (- (System/nanoTime) started-ns)) 1000000.0))

(defn observe!
  "Call the `:observe` callback in `opts` with `event`, ignoring errors."
  [opts event]
  (when-let [observe (:observe opts)]
    (try
      (observe event)
      (catch Throwable _ nil))))

(defn event
  "Build one observation event map. `base` supplies the emitter-specific
  `:kind`, `:name`, and `:attributes`; `attrs` is the options map plus any
  per-phase additions (`:duration-ms`, `:ok?`, `:error`)."
  [phase span-id parent-span-id {:keys [kind name attributes]} attrs]
  (cond-> {:karcarthy/type :event
           :event          phase
           :kind           kind
           :name           name
           :span/kind      :internal
           :span/id        span-id
           :time-ms        (now-ms)
           :path           (:karcarthy/path attrs)
           :attributes     (cond-> attributes
                             (:karcarthy/path attrs)
                             (assoc "karcarthy.path" (pr-str (:karcarthy/path attrs))))}
    parent-span-id       (assoc :parent/span-id parent-span-id)
    (:duration-ms attrs) (assoc :duration-ms (:duration-ms attrs))
    (contains? attrs :ok?) (assoc :ok? (:ok? attrs))
    (:error attrs)       (assoc :error (:error attrs))))

(defn with-span
  "Run `(body span-id)` bracketed by :start/:finish/:error events built by
  `(make-event phase span-id parent-span-id attrs)`. When `opts` has no
  `:observe` callback, calls `body` with nil and emits nothing."
  [opts make-event body]
  (if-not (:observe opts)
    (body nil)
    (let [span-id        (span-id)
          parent-span-id (:karcarthy/parent-span-id opts)
          started-ns     (System/nanoTime)]
      (observe! opts (make-event :start span-id parent-span-id opts))
      (try
        (let [result (body span-id)]
          (observe! opts (make-event :finish span-id parent-span-id
                                     (cond-> (assoc opts
                                                    :duration-ms (duration-ms started-ns)
                                                    :ok? (true? (:ok? result)))
                                       (:error result) (assoc :error (:error result)))))
          result)
        (catch Throwable t
          (observe! opts (make-event :error span-id parent-span-id
                                     (assoc opts
                                            :duration-ms (duration-ms started-ns)
                                            :ok? false
                                            :error (or (ex-message t) (str t)))))
          (throw t))))))

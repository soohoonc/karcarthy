(ns karcarthy.observe
  "Internal helpers for emitting observation events.

  When a run is given an `:observe` callback in its options, `karcarthy.core`
  and `karcarthy.orchestrate` emit OTel-shaped event maps through these
  helpers. Observer errors are swallowed so observation can never fail a run."
  (:import [java.util UUID]))

(defn span-id [] (str (UUID/randomUUID)))

(defn now-ms [] (System/currentTimeMillis))

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

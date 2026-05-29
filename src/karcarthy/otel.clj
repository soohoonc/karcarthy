(ns karcarthy.otel
  "OpenTelemetry instrumentation for karcarthy.

  The public entrypoint is `instrument`, which wraps a runner so workflow nodes,
  embedded functions, and agent calls produce OpenTelemetry spans. Applications
  provide their own OpenTelemetry SDK/exporter setup; without one, the global
  OpenTelemetry instance is a no-op, matching the Java API's normal behavior."
  (:require [clojure.string :as str]
            [karcarthy.core :as k])
  (:import [io.opentelemetry.api GlobalOpenTelemetry OpenTelemetry]
           [io.opentelemetry.api.common Attributes AttributesBuilder]
           [io.opentelemetry.api.trace Span StatusCode Tracer]))

(def ^:private default-instrumentation-name "karcarthy")
(def ^:private default-instrumentation-version "0.0.2")
(def ^:private preview-limit 180)

(defprotocol ^:no-doc InstrumentedRunner
  (-otel-defaults [runner]))

(defn- preview [x]
  (let [s (str/replace (str x) #"\s+" " ")]
    (if (> (count s) preview-limit)
      (str (subs s 0 preview-limit) "...")
      s)))

(defn- tracer-from
  ^Tracer [{:keys [tracer open-telemetry instrumentation-name instrumentation-version]
            :or   {instrumentation-name    default-instrumentation-name
                   instrumentation-version default-instrumentation-version}}]
  (or tracer
      (let [^OpenTelemetry otel (or open-telemetry (GlobalOpenTelemetry/get))]
        (.getTracer otel instrumentation-name instrumentation-version))))

(defn- normalize-opts [opts]
  (cond-> opts
    (not (:otel/tracer opts)) (assoc :otel/tracer (tracer-from opts))))

(defn ^:no-doc instrumented-opts
  "Merge OTel defaults from an instrumented runner into run opts."
  [runner opts]
  (cond
    (:otel/tracer opts) opts
    (or (:tracer opts) (:open-telemetry opts)) (normalize-opts opts)
    (satisfies? InstrumentedRunner runner) (merge (-otel-defaults runner) opts)
    :else opts))

(defn- attr-name [k]
  (cond
    (keyword? k) (if-let [ns (namespace k)] (str ns "." (name k)) (name k))
    :else (str k)))

(defn- put-attr! [^AttributesBuilder b k v]
  (let [k (attr-name k)]
    (cond
      (nil? v) b
      (string? v) (.put b ^String k ^String v)
      (keyword? v) (.put b ^String k ^String (name v))
      (boolean? v) (.put b ^String k (boolean v))
      (integer? v) (.put b ^String k (long v))
      (float? v) (.put b ^String k (double v))
      :else (.put b ^String k ^String (str v)))))

(defn- attributes [m]
  (let [b (Attributes/builder)]
    (doseq [[k v] m] (put-attr! b k v))
    (.build b)))

(defn- set-attr! [^Span span k v]
  (let [k (attr-name k)]
    (cond
      (nil? v) span
      (string? v) (.setAttribute span ^String k ^String v)
      (keyword? v) (.setAttribute span ^String k ^String (name v))
      (boolean? v) (.setAttribute span ^String k (boolean v))
      (integer? v) (.setAttribute span ^String k (long v))
      (float? v) (.setAttribute span ^String k (double v))
      :else (.setAttribute span ^String k ^String (str v)))))

(defn ^:no-doc with-child-path
  "Append path segments to the current workflow path."
  [opts segment]
  (if (:otel/tracer opts)
    (update opts :otel/path (fnil into []) (if (sequential? segment) segment [segment]))
    opts))

(defn- path-string [opts]
  (when-let [path (seq (:otel/path opts))]
    (str/join "." (map #(if (keyword? %) (name %) %) path))))

(defn- maybe-preview [opts k v]
  (when (:capture-previews? opts)
    {k (preview v)}))

(defn ^:no-doc with-span
  "Run `thunk` in an OpenTelemetry span when instrumentation is active."
  [opts span-name attrs thunk]
  (if-let [^Tracer tracer (:otel/tracer opts)]
    (let [span (-> tracer
                   (.spanBuilder span-name)
                   (.setAllAttributes (attributes attrs))
                   (.startSpan))]
      (try
        (let [scope (.makeCurrent span)]
          (try
            (thunk span)
            (finally
              (.close scope))))
        (catch Throwable t
          (.recordException span t)
          (.setStatus span StatusCode/ERROR (or (ex-message t) (str t)))
          (throw t))
        (finally
          (.end span))))
    (thunk nil)))

(defn ^:no-doc with-workflow-span [opts workflow input thunk]
  (with-span opts "karcarthy.workflow"
    (merge {"karcarthy.workflow.type" (name (:karcarthy/type workflow))
            "karcarthy.workflow.path" (or (path-string opts) "")
            "karcarthy.agent.name" (:name workflow)}
           (maybe-preview opts "karcarthy.input.preview" input))
    (fn [span]
      (let [r (thunk)]
        (when span
          (set-attr! span "karcarthy.result.ok" (boolean (:ok? r)))
          (set-attr! span "karcarthy.result.agent" (:agent r))
          (set-attr! span "karcarthy.error" (:error r))
          (when (false? (:ok? r))
            (.setStatus span StatusCode/ERROR (str (or (:error r) "karcarthy workflow returned not-ok"))))
          (doseq [[k v] (maybe-preview opts "karcarthy.output.preview" (:text r))]
            (set-attr! span k v)))
        r))))

(defn ^:no-doc with-flow-span
  "Deprecated alias for `with-workflow-span`."
  [opts flow input thunk]
  (with-workflow-span opts flow input thunk))

(defn ^:no-doc with-agent-span [opts agent prompt thunk]
  (with-span opts "karcarthy.agent"
    (merge {"karcarthy.agent.name" (:name agent)
            "karcarthy.agent.model" (:model agent)
            "karcarthy.agent.runner" (some-> (or (:runner agent) (:harness agent)) name)
            "karcarthy.workflow.path" (or (path-string opts) "")}
           (maybe-preview opts "karcarthy.prompt.preview" prompt))
    (fn [span]
      (let [r (thunk)]
        (when span
          (set-attr! span "karcarthy.result.ok" (boolean (:ok? r)))
          (set-attr! span "karcarthy.result.agent" (:agent r))
          (set-attr! span "karcarthy.error" (:error r))
          (when (false? (:ok? r))
            (.setStatus span StatusCode/ERROR (str (or (:error r) "karcarthy agent returned not-ok"))))
          (set-attr! span "gen_ai.usage.input_tokens" (get-in r [:usage :input_tokens]))
          (set-attr! span "gen_ai.usage.output_tokens" (get-in r [:usage :output_tokens]))
          (set-attr! span "karcarthy.cost.usd" (:cost-usd r))
          (set-attr! span "karcarthy.turns.count" (:num-turns r))
          (set-attr! span "karcarthy.session.id" (:session-id r))
          (doseq [[k v] (maybe-preview opts "karcarthy.output.preview" (:text r))]
            (set-attr! span k v)))
        r))))

(defn ^:no-doc with-function-span [opts label f thunk]
  (with-span opts "karcarthy.function"
    {"karcarthy.function.label" (attr-name label)
     "karcarthy.function.class" (.getName (class f))
     "karcarthy.workflow.path" (or (path-string opts) "")}
    (fn [span]
      (let [v (thunk)]
        (when span
          (doseq [[k v] (maybe-preview opts "karcarthy.output.preview" v)]
            (set-attr! span k v)))
        v))))

(defn instrument
  "Wrap a runner or runner registry with OpenTelemetry instrumentation.

  Options:
    :open-telemetry      an io.opentelemetry.api.OpenTelemetry instance
    :tracer              an io.opentelemetry.api.trace.Tracer instance
    :instrumentation-name / :instrumentation-version
    :capture-previews?   include compact input/prompt/output previews

  If neither :open-telemetry nor :tracer is supplied, the global OpenTelemetry
  instance is used. With no configured SDK/exporter, this is a no-op."
  ([runner] (instrument runner {}))
  ([runner opts]
   (let [defaults (normalize-opts opts)]
     (reify
       InstrumentedRunner
       (-otel-defaults [_] defaults)

       k/Runner
       (-run [_ agent prompt opts]
         (let [opts' (merge defaults opts)]
           (with-agent-span opts' agent prompt
             #(k/-run (k/resolve-runner runner agent) agent prompt opts'))))))))

(ns karcarthy.otel-test
  (:require [clojure.test :refer [deftest is testing]]
            [karcarthy.core :as k]
            [karcarthy.orchestrate :as o]
            [karcarthy.otel :as otel])
  (:import [io.opentelemetry.api.common AttributeKey]
           [io.opentelemetry.sdk OpenTelemetrySdk]
           [io.opentelemetry.sdk.testing.exporter InMemorySpanExporter]
           [io.opentelemetry.sdk.trace SdkTracerProvider]
           [io.opentelemetry.sdk.trace.export SimpleSpanProcessor]))

(def ^:private a (k/agent "a" "first"))
(def ^:private b (k/agent "b" "second"))

(defn- test-otel []
  (let [exporter (InMemorySpanExporter/create)
        provider (-> (SdkTracerProvider/builder)
                     (.addSpanProcessor (SimpleSpanProcessor/create exporter))
                     (.build))
        otel     (-> (OpenTelemetrySdk/builder)
                     (.setTracerProvider provider)
                     (.build))]
    {:open-telemetry otel
     :exporter       exporter
     :provider       provider}))

(defn- spans [exporter]
  (vec (.getFinishedSpanItems exporter)))

(defn- attr [span key]
  (.get (.getAttributes span) (AttributeKey/stringKey key)))

(defn- bool-attr [span key]
  (.get (.getAttributes span) (AttributeKey/booleanKey key)))

(deftest instrument-emits-flow-and-agent-spans
  (testing "one instrument call captures flow nodes and agent invocations"
    (let [{:keys [open-telemetry exporter provider]} (test-otel)
          h (otel/instrument (k/mock-harness) {:open-telemetry open-telemetry})
          r (o/run-flow h (o/chain a b) "hi")
          done (spans exporter)
          names (frequencies (map #(.getName %) done))
          agent-spans (filter #(= "karcarthy.agent" (.getName %)) done)]
      (try
        (is (k/ok? r))
        (is (= {"karcarthy.flow" 3 "karcarthy.agent" 2} names))
        (is (= ["a" "b"] (mapv #(attr % "karcarthy.agent.name") agent-spans)))
        (is (= ["steps.0" "steps.1"] (mapv #(attr % "karcarthy.flow.path") agent-spans)))
        (is (every? #(= true (bool-attr % "karcarthy.result.ok")) done))
        (finally
          (.shutdown provider))))))

(deftest instrument-emits-function-spans
  (testing "function routers and gatherers are visible as function spans"
    (let [{:keys [open-telemetry exporter provider]} (test-otel)
          flow (o/route (fn [_] "yes")
                        {"yes" (o/parallel* [a b]
                                            :gather (fn [rs]
                                                      (k/result {:text (count rs)})))})
          r (o/run-flow (otel/instrument (k/mock-harness) {:open-telemetry open-telemetry})
                        flow "hi")
          labels (->> (spans exporter)
                      (filter #(= "karcarthy.function" (.getName %)))
                      (map #(attr % "karcarthy.function.label"))
                      set)]
      (try
        (is (k/ok? r))
        (is (= #{"route.router" "parallel.gather"} labels))
        (finally
          (.shutdown provider))))))

(ns karcarthy.test-runner
  "Zero-dependency test runner (no Clojars needed). Add new test namespaces to
  `test-namespaces` below."
  (:require [clojure.test :as t]))

(def test-namespaces
  '[karcarthy-test
    karcarthy.core-test
    karcarthy.cli-test
    karcarthy.proc-test
    karcarthy.harness.claude-test
    karcarthy.harness.command-test
    karcarthy.harness.openai-test
    karcarthy.orchestrate-test
    karcarthy.self-test
    karcarthy.session-test
    karcarthy.otel-test])

(defn -main [& _]
  (doseq [ns test-namespaces] (require ns))
  (let [{:keys [fail error]} (apply t/run-tests test-namespaces)]
    (shutdown-agents)
    (System/exit (if (pos? (+ (or fail 0) (or error 0))) 1 0))))

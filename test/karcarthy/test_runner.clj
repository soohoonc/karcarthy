(ns karcarthy.test-runner
  (:require [clojure.test :as t]))

(def test-namespaces
  '[karcarthy-test
    karcarthy.core-test
    karcarthy.cli-test
    karcarthy.coding-test
    karcarthy.eval-test
    karcarthy.openai-test
    karcarthy.mcp-test
    karcarthy.acp-test])

(defn -main [& _]
  (doseq [ns test-namespaces] (require ns))
  (let [{:keys [fail error]} (apply t/run-tests test-namespaces)]
    (shutdown-agents)
    (System/exit (if (pos? (+ (or fail 0) (or error 0))) 1 0))))

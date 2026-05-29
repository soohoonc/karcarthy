(ns karcarthy-test
  (:require [clojure.test :refer [deftest is testing]]
            [karcarthy :as kc]))

(deftest facade-reexports-fns
  (testing "core + orchestrate functions are reachable under one alias"
    (let [a (kc/agent "x" "i")]
      (is (kc/agent? a)))
    (let [r (kc/run-flow (kc/mock-harness)
                         (kc/chain (kc/agent "a" "i") (kc/agent "b" "i"))
                         "hi")]
      (is (kc/ok? r))
      (is (= "[b] [a] hi" (:text r))))))

(deftest facade-reexports-macros
  (testing "defagent is re-exported as a working macro"
    (kc/defagent facade-agent "instr" :model "m")
    (is (= "facade-agent" (:name facade-agent)))
    (is (= "m" (:model facade-agent)))))

(deftest facade-reexports-values
  (is (string? kc/dsl-reference)))

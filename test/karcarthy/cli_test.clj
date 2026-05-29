(ns karcarthy.cli-test
  (:require [clojure.test :refer [deftest is testing]]
            [karcarthy.core :as k]
            [karcarthy.orchestrate :as o]
            [karcarthy.cli :as cli]))

(deftest json->flow-builds-and-runs
  (testing "a JSON chain translates to flow data and runs on the mock harness"
    (let [f (cli/json->flow {"type"  "chain"
                             "steps" [{"type" "agent" "name" "a" "instructions" "i"}
                                      {"type" "agent" "name" "b" "instructions" "i"}]})]
      (is (= :chain (:karcarthy/type f)))
      (is (k/agent? (first (:steps f))))
      (is (= "[b] [a] hi" (:text (o/run-flow (k/mock-harness) f "hi")))))))

(deftest json->flow-agent-fields
  (let [a (cli/json->flow {"type" "agent" "name" "x" "instructions" "do"
                           "model" "haiku" "harness" "claude"})]
    (is (= "x" (:name a)))
    (is (= "haiku" (:model a)))
    (is (= :claude (:harness a)))))

(deftest json->flow-route-keeps-string-labels
  (testing "route labels stay strings (not coerced to keywords)"
    (let [f (cli/json->flow {"type"   "route"
                             "router" {"type" "agent" "name" "r" "instructions" "i"}
                             "routes" {"billing" {"type" "agent" "name" "bill" "instructions" "i"}}})]
      (is (= :route (:karcarthy/type f)))
      (is (contains? (:routes f) "billing")))))

(deftest json->flow-unknown-type
  (is (thrown? clojure.lang.ExceptionInfo (cli/json->flow {"type" "nope"}))))

(ns karcarthy.cli-test
  (:require [clojure.test :refer [deftest is testing]]
            [karcarthy.core :as k]
            [karcarthy.orchestrate :as o]
            [karcarthy.cli :as cli]))

(deftest json->workflow-builds-and-runs
  (testing "a JSON pipe translates to workflow data and runs on the mock adapter"
    (let [workflow (cli/json->workflow {"type"  "pipe"
                                        "steps" [{"type" "agent" "name" "a" "instructions" "i"}
                                                 {"type" "agent" "name" "b" "instructions" "i"}]})]
      (is (= :pipe (:karcarthy/type workflow)))
      (is (k/agent? (first (:steps workflow))))
      (is (= "[b] [a] hi" (:text (o/run (k/mock-adapter) workflow "hi")))))))

(deftest json->workflow-accepts-map-and-bind
  (testing "JSON map and bind names compile to runnable workflow data"
    (let [mapped (cli/json->workflow {"type"     "map"
                                      "branches" [{"type" "agent" "name" "a" "instructions" "i"}
                                                  {"type" "agent" "name" "b" "instructions" "i"}]})
          bound  (cli/json->workflow {"type"   "bind"
                                      "source" {"type" "agent" "name" "router" "instructions" "i"}
                                      "routes" {"billing" {"type" "agent" "name" "bill" "instructions" "i"}}})]
      (is (= :map (:karcarthy/type mapped)))
      (is (= :bind (:karcarthy/type bound)))
      (is (contains? (:routes bound) "billing")))))

(deftest json->workflow-agent-fields
  (let [a (cli/json->workflow {"type" "agent" "name" "x" "instructions" "do"
                               "model" "haiku" "adapter" "claude"})]
    (is (= "x" (:name a)))
    (is (= "haiku" (:model a)))
    (is (= :claude (:adapter a)))))

(deftest json->workflow-accepts-old-harness-field
  (let [a (cli/json->workflow {"type" "agent" "name" "x" "instructions" "do"
                               "harness" "claude"})]
    (is (= :claude (:adapter a)))
    (is (= :claude (:harness a)))))

(deftest json->workflow-route-keeps-string-labels
  (testing "route labels stay strings (not coerced to keywords)"
    (let [workflow (cli/json->workflow {"type"   "route"
                                        "router" {"type" "agent" "name" "r" "instructions" "i"}
                                        "routes" {"billing" {"type" "agent" "name" "bill" "instructions" "i"}}})]
      (is (= :bind (:karcarthy/type workflow)))
      (is (contains? (:routes workflow) "billing")))))

(deftest json->workflow-unknown-type
  (is (thrown? clojure.lang.ExceptionInfo (cli/json->workflow {"type" "nope"}))))

(deftest json->flow-compatibility-alias
  (let [workflow (cli/json->flow {"type" "agent" "name" "x" "instructions" "do"})]
    (is (k/agent? workflow))
    (is (= "x" (:name workflow)))))

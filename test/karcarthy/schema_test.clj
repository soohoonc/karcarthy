(ns karcarthy.schema-test
  (:require [clojure.test :refer [deftest is testing]]
            [karcarthy.schema :as schema]))

(deftest schema-values-are-data
  (testing "schema references are plain inspectable maps"
    (is (= :agent (get-in schema/edn-schema [:agent :karcarthy/type])))
    (is (= :subagent (get-in schema/edn-schema [:subagent :karcarthy/type])))
    (is (= false (get-in schema/edn-schema [:subagent :workflow-node?])))
    (is (= :workflow (get-in schema/edn-schema [:pipe :required :steps 1])))
    (is (= :step (get-in schema/edn-schema [:step :karcarthy/type])))
    (is (= false (get-in schema/edn-schema [:step :serializable?])))
    (is (= :dynamic (get-in schema/edn-schema [:dynamic :karcarthy/type])))
    (is (= :agent-ref (get-in schema/edn-schema [:agent-ref :karcarthy/type])))
    (is (= "https://json-schema.org/draft/2020-12/schema"
           (get schema/json-schema "$schema")))
    (is (= "#/$defs/workflow" (get schema/json-schema "$ref")))
    (is (contains? (get-in schema/json-schema ["$defs"]) "dynamic"))))

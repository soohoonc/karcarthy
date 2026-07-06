(ns karcarthy.schema-test
  (:require [clojure.test :refer [deftest is testing]]
            [karcarthy.schema :as schema]
            [karcarthy.workflow :as workflow]))

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

(deftest grammar-drives-every-public-node-shape
  (doseq [type workflow/workflow-types
          :let [spec (workflow/node-spec type)
                fields (set (keys (:fields spec)))]]
    (is (= fields
           (set (concat (keys (get-in schema/edn-schema [type :required]))
                        (keys (get-in schema/edn-schema [type :optional])))))
        (str type " EDN fields drifted from the workflow grammar"))
    (when (:json? spec)
      (let [json-name (name type)
            properties (get-in schema/json-schema ["$defs" json-name "properties"])]
        (is (= (conj (set (map name fields)) "type")
               (set (keys properties)))
            (str type " JSON properties drifted from the workflow grammar"))
        (is (= (get (workflow/json-node-keys) json-name)
               (set (keys properties)))
            (str type " CLI allowlist drifted from JSON Schema"))))))

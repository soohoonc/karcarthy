(ns karcarthy.conformance-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [karcarthy :as k]
            [karcarthy.tool :as tool-data]))

(def extension-key
  (gen/fmap #(keyword "example" %)
            (gen/such-that seq gen/string-alphanumeric)))

(deftest namespaced-configuration-keys-are-preserved
  (let [result
        (tc/quick-check
         100
         (prop/for-all
          [key extension-key]
          (let [model (k/mock-model
                       (constantly {:type :final :output "ok"}))
                tool (tool-data/make-tool
                      (assoc {:name "extension-tool"
                              :description "Exercise extension data."
                              :input-schema map?
                              :output-schema string?}
                             key true)
                      nil nil (fn [_ _] "ok"))
                agent (k/agent
                       (assoc {:name "extension-agent"
                               :model {:id "fake" :transport model}
                               :instructions "Answer."
                               :tools [tool]
                               :output-schema string?}
                              key true))
                run (k/run! agent "input" {key true})]
            (and (true? (get tool key))
                 (true? (get agent key))
                 (= :completed (:status run))))))]
    (is (:pass? result) (pr-str result))))

(ns karcarthy.adapter.openai-test
  (:require [clojure.test :refer [deftest is testing]]
            [karcarthy.core :as k]
            [karcarthy.adapter.openai :as oa]))

(deftest request-builder
  (testing "agent fields map into the adapter request"
    (is (= {:name "writer" :instructions "Write well." :input "hello" :model "gpt-4o-mini"}
           (oa/openai-request (k/agent "writer" "Write well." :model "gpt-4o-mini")
                              "hello" {}))))
  (testing "opts :model overrides the agent model"
    (is (= "gpt-4o-mini"
           (:model (oa/openai-request (k/agent "w" "i" :model "gpt-4o") "p"
                                      {:model "gpt-4o-mini"})))))
  (testing "no model key when neither opts nor agent set one"
    (is (not (contains? (oa/openai-request (k/agent "w" "i") "p" {}) :model)))))

(deftest parse-ok
  (let [r (oa/parse-openai-result "writer" "{\"ok\":true,\"text\":\"hi there\"}")]
    (is (k/ok? r))
    (is (= :result (:karcarthy/type r)))
    (is (= "writer" (:agent r)))
    (is (= "hi there" (:text r)))))

(deftest parse-error
  (let [r (oa/parse-openai-result "writer" "{\"ok\":false,\"error\":\"no api key\"}")]
    (is (not (k/ok? r)))
    (is (= "no api key" (:error r)))))

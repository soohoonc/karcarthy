(ns karcarthy.runner.openai-test
  (:require [clojure.test :refer [deftest is testing]]
            [karcarthy.core :as k]
            [karcarthy.runner.openai :as oa]))

(deftest request-builder
  (testing "agent fields map into the runner request"
    (is (= {:name "writer" :instructions "Write well." :input "hello" :model "gpt-4o-mini"}
           (oa/request (k/agent "writer" "Write well." :model "gpt-4o-mini")
                       "hello" {}))))
  (testing "opts :model overrides the agent model"
    (is (= "gpt-4o-mini"
           (:model (oa/request (k/agent "w" "i" :model "gpt-4o") "p"
                               {:model "gpt-4o-mini"})))))
  (testing "no model key when neither opts nor agent set one"
    (is (not (contains? (oa/request (k/agent "w" "i") "p" {}) :model)))))

(deftest request-builder-subagents
  (testing "subagents map to OpenAI handoff agents"
    (let [reviewer (k/subagent "security-reviewer"
                               "Use for security review."
                               "Find concrete security risks."
                               :model "gpt-5.4-mini")]
      (is (= [{:name "security-reviewer"
               :instructions "Find concrete security risks."
               :handoff_description "Use for security review."
               :model "gpt-5.4-mini"}]
             (:subagents
              (oa/request (k/agent "lead" "Coordinate.")
                          "review"
                          {:subagents [reviewer]})))))))

(deftest stdout->result-ok
  (let [r (oa/stdout->result "writer" "{\"ok\":true,\"text\":\"hi there\"}")]
    (is (k/ok? r))
    (is (= :result (:karcarthy/type r)))
    (is (= "writer" (:agent r)))
    (is (= "hi there" (:text r)))))

(deftest stdout->result-error
  (let [r (oa/stdout->result "writer" "{\"ok\":false,\"error\":\"no api key\"}")]
    (is (not (k/ok? r)))
    (is (= "no api key" (:error r)))))

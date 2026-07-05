(ns karcarthy.runner.openai-test
  (:require [clojure.test :refer [deftest is testing]]
            [karcarthy.core :as k]
            [karcarthy.runner.openai :as oa]))

(deftest request-builder
  (testing "agent fields map into the runner request"
    (is (= {:name "writer" :instructions "Write well." :input "hello" :model "gpt-4o-mini"}
           (oa/request (k/agent {:name "writer"
                                 :instructions "Write well."
                                 :model "gpt-4o-mini"})
                       "hello" {}))))
  (testing "opts :model overrides the agent model"
    (is (= "gpt-4o-mini"
           (:model (oa/request (k/agent {:name "w"
                                         :instructions "i"
                                         :model "gpt-4o"})
                               "p"
                               {:model "gpt-4o-mini"})))))
  (testing "no model key when neither opts nor agent set one"
    (is (not (contains? (oa/request (k/agent {:name "w" :instructions "i"})
                                    "p"
                                    {})
                        :model)))))

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
              (oa/request (k/agent {:name "lead" :instructions "Coordinate."})
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

(deftest stdout->result-requires-explicit-status
  (let [missing (oa/stdout->result "writer" "{\"text\":\"looks good\"}")
        stringy (oa/stdout->result "writer" "{\"ok\":\"false\",\"text\":\"bad\"}")]
    (is (not (k/ok? missing)))
    (is (not (k/ok? stringy)))
    (is (re-find #"Boolean ok" (:error missing)))))

(deftest nonzero-exit-overrides-a-success-payload
  (let [script (java.io.File/createTempFile "fake-openai" ".py")]
    (try
      (spit script (str "import sys\n"
                        "print('{\"ok\": true, \"text\": \"looks good\"}')\n"
                        "sys.exit(9)\n"))
      (let [runner (oa/openai-runner {:script (.getPath script)})
            r (k/run-agent runner (k/agent {:name "a" :instructions "i"}) "x")]
        (is (not (k/ok? r)))
        (is (re-find #"exited with status 9" (:error r)))
        (is (= "looks good" (:text r))))
      (finally (.delete script)))))

(ns karcarthy.runner.acp-test
  (:require [clojure.test :refer [deftest is testing]]
            [karcarthy.core :as k]
            [karcarthy.runner.acp :as acp]))

(defn- script-runner [script]
  (acp/acp-runner {:command ["sh" "-c" script]
                   :timeout-ms 3000}))

(deftest prompt-text-includes-agent-spec
  (testing "karcarthy agent instructions are folded into the ACP prompt turn"
    (let [agent (k/agent {:name "reviewer"
                          :description "Use for code review."
                          :instructions "Find concrete bugs."})
          text  (acp/prompt-text agent "review this diff" {})]
      (is (re-find #"Karcarthy agent: reviewer" text))
      (is (re-find #"Description:\nUse for code review" text))
      (is (re-find #"Instructions:\nFind concrete bugs" text))
      (is (re-find #"User input:\nreview this diff" text)))))

(deftest acp-runner-runs-one-prompt-turn
  (testing "stdio ACP lifecycle returns collected agent message chunks"
    (let [script (str
                  "read init\n"
                  "printf '%s\\n' '{\"jsonrpc\":\"2.0\",\"id\":0,\"result\":{\"protocolVersion\":1,\"agentCapabilities\":{},\"agentInfo\":{\"name\":\"fake-acp\"}}}'\n"
                  "read session\n"
                  "printf '%s\\n' '{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"sessionId\":\"S1\"}}'\n"
                  "read prompt\n"
                  "printf '%s\\n' '{\"jsonrpc\":\"2.0\",\"method\":\"session/update\",\"params\":{\"sessionId\":\"S1\",\"update\":{\"sessionUpdate\":\"agent_message_chunk\",\"messageId\":\"m1\",\"content\":{\"type\":\"text\",\"text\":\"hello \"}}}}'\n"
                  "printf '%s\\n' '{\"jsonrpc\":\"2.0\",\"method\":\"session/update\",\"params\":{\"sessionId\":\"S1\",\"update\":{\"sessionUpdate\":\"agent_message_chunk\",\"messageId\":\"m1\",\"content\":{\"type\":\"text\",\"text\":\"from acp\"}}}}'\n"
                  "printf '%s\\n' '{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"stopReason\":\"end_turn\"}}'\n")
          runner (script-runner script)
          agent  (k/agent {:name "a" :instructions "i"})
          result (k/run-agent runner agent "hi")]
      (is (k/ok? result))
      (is (= "hello from acp" (:text result)))
      (is (= "S1" (:session-id result)))
      (is (= "end_turn" (:stop-reason result)))
      (is (= :acp (get-in result [:raw :runner]))))))

(deftest acp-runner-handles-agent-permission-requests
  (testing "agent->client permission requests receive a JSON-RPC response"
    (let [script (str
                  "read init\n"
                  "printf '%s\\n' '{\"jsonrpc\":\"2.0\",\"id\":0,\"result\":{\"protocolVersion\":1,\"agentCapabilities\":{}}}'\n"
                  "read session\n"
                  "printf '%s\\n' '{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"sessionId\":\"S1\"}}'\n"
                  "read prompt\n"
                  "printf '%s\\n' '{\"jsonrpc\":\"2.0\",\"id\":77,\"method\":\"session/request_permission\",\"params\":{\"sessionId\":\"S1\",\"toolCall\":{\"toolCallId\":\"t1\"},\"options\":[{\"optionId\":\"allow-once\",\"name\":\"Allow\",\"kind\":\"allow_once\"}]}}'\n"
                  "read permission\n"
                  "printf '%s\\n' '{\"jsonrpc\":\"2.0\",\"method\":\"session/update\",\"params\":{\"sessionId\":\"S1\",\"update\":{\"sessionUpdate\":\"agent_message_chunk\",\"messageId\":\"m1\",\"content\":{\"type\":\"text\",\"text\":\"done\"}}}}'\n"
                  "printf '%s\\n' '{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"stopReason\":\"end_turn\"}}'\n")
          runner (script-runner script)
          result (k/run-agent runner (k/agent {:name "a" :instructions "i"}) "hi")]
      (is (k/ok? result))
      (is (= "done" (:text result))))))

(deftest acp-runner-sets-model-through-config-options
  (testing "agent :model is mapped to an ACP model config option when advertised"
    (let [script (str
                  "read init\n"
                  "printf '%s\\n' '{\"jsonrpc\":\"2.0\",\"id\":0,\"result\":{\"protocolVersion\":1,\"agentCapabilities\":{}}}'\n"
                  "read session\n"
                  "printf '%s\\n' '{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"sessionId\":\"S1\",\"configOptions\":[{\"id\":\"model\",\"name\":\"Model\",\"category\":\"model\",\"type\":\"select\",\"currentValue\":\"old\",\"options\":[{\"value\":\"sonnet\",\"name\":\"Sonnet\"}]}]}}'\n"
                  "read config\n"
                  "printf '%s\\n' '{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"configOptions\":[{\"id\":\"model\",\"name\":\"Model\",\"category\":\"model\",\"type\":\"select\",\"currentValue\":\"sonnet\",\"options\":[]}]}}'\n"
                  "read prompt\n"
                  "printf '%s\\n' '{\"jsonrpc\":\"2.0\",\"method\":\"session/update\",\"params\":{\"sessionId\":\"S1\",\"update\":{\"sessionUpdate\":\"agent_message_chunk\",\"messageId\":\"m1\",\"content\":{\"type\":\"text\",\"text\":\"configured\"}}}}'\n"
                  "printf '%s\\n' '{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"stopReason\":\"end_turn\"}}'\n")
          runner (script-runner script)
          agent  (k/agent {:name "a" :instructions "i" :model "sonnet"})
          result (k/run-agent runner agent "hi")]
      (is (k/ok? result))
      (is (= "configured" (:text result))))))

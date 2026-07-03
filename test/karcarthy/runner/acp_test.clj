(ns karcarthy.runner.acp-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
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

(deftest acp-connection-reuses-one-process-across-runs
  (testing "connect! pays initialize once; each run is one session + prompt turn"
    (let [script (str
                  "read init\n"
                  "printf '%s\\n' '{\"jsonrpc\":\"2.0\",\"id\":0,\"result\":{\"protocolVersion\":1,\"agentCapabilities\":{},\"agentInfo\":{\"name\":\"fake-acp\"}}}'\n"
                  "read s1\n"
                  "printf '%s\\n' '{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"sessionId\":\"S1\"}}'\n"
                  "read p1\n"
                  "printf '%s\\n' '{\"jsonrpc\":\"2.0\",\"method\":\"session/update\",\"params\":{\"sessionId\":\"S1\",\"update\":{\"sessionUpdate\":\"agent_message_chunk\",\"content\":{\"type\":\"text\",\"text\":\"one\"}}}}'\n"
                  "printf '%s\\n' '{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"stopReason\":\"end_turn\"}}'\n"
                  "read s2\n"
                  "printf '%s\\n' '{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"sessionId\":\"S2\"}}'\n"
                  "read p2\n"
                  "printf '%s\\n' '{\"jsonrpc\":\"2.0\",\"method\":\"session/update\",\"params\":{\"sessionId\":\"S2\",\"update\":{\"sessionUpdate\":\"agent_message_chunk\",\"content\":{\"type\":\"text\",\"text\":\"two\"}}}}'\n"
                  "printf '%s\\n' '{\"jsonrpc\":\"2.0\",\"id\":4,\"result\":{\"stopReason\":\"end_turn\"}}'\n")
          connection (acp/connect! {:command ["sh" "-c" script] :timeout-ms 3000})
          runner     (acp/acp-runner {:connection connection :timeout-ms 3000})
          agent      (k/agent {:name "a" :instructions "i"})]
      (try
        (let [r1 (k/run-agent runner agent "first")
              r2 (k/run-agent runner agent "second")]
          (is (k/ok? r1))
          (is (= "one" (:text r1)))
          (is (= "S1" (:session-id r1)))
          (is (k/ok? r2))
          (is (= "two" (:text r2)))
          (is (= "S2" (:session-id r2))))
        (finally
          (acp/close! connection))))))

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

(deftest session-load-replay-stays-out-of-text
  (testing "history replayed by session/load lands in :raw :updates, not :text"
    (let [script (str
                  "read init\n"
                  "printf '%s\\n' '{\"jsonrpc\":\"2.0\",\"id\":0,\"result\":{\"protocolVersion\":1,\"agentCapabilities\":{\"loadSession\":true}}}'\n"
                  "read load\n"
                  "printf '%s\\n' '{\"jsonrpc\":\"2.0\",\"method\":\"session/update\",\"params\":{\"sessionId\":\"S1\",\"update\":{\"sessionUpdate\":\"agent_message_chunk\",\"content\":{\"type\":\"text\",\"text\":\"old turn\"}}}}'\n"
                  "printf '%s\\n' '{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":null}'\n"
                  "read prompt\n"
                  "printf '%s\\n' '{\"jsonrpc\":\"2.0\",\"method\":\"session/update\",\"params\":{\"sessionId\":\"S1\",\"update\":{\"sessionUpdate\":\"agent_message_chunk\",\"content\":{\"type\":\"text\",\"text\":\"new answer\"}}}}'\n"
                  "printf '%s\\n' '{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"stopReason\":\"end_turn\"}}'\n")
          runner (acp/acp-runner {:command ["sh" "-c" script]
                                  :timeout-ms 3000
                                  :resume "S1"})
          result (k/run-agent runner (k/agent {:name "a" :instructions "i"}) "hi")]
      (is (k/ok? result))
      (is (= "new answer" (:text result)))
      (is (= "S1" (:session-id result)))
      (is (= ["old turn" "new answer"]
             (mapv #(get-in % [:content :text])
                   (get-in result [:raw :updates])))))))

(deftest explicit-false-resume-capability-is-unsupported
  (testing "an agent advertising {\"resume\": false} is never sent session/resume"
    (let [script (str
                  "read init\n"
                  "printf '%s\\n' '{\"jsonrpc\":\"2.0\",\"id\":0,\"result\":{\"protocolVersion\":1,\"agentCapabilities\":{\"sessionCapabilities\":{\"resume\":false}}}}'\n")
          runner (acp/acp-runner {:command ["sh" "-c" script]
                                  :timeout-ms 3000
                                  :resume "S1"})
          result (k/run-agent runner (k/agent {:name "a" :instructions "i"}) "hi")]
      (is (not (k/ok? result)))
      (is (re-find #"does not support session resume/load" (:error result))))))

(deftest additional-directories-gated-on-capability-value
  (testing "additionalDirectories is sent only when advertised as true"
    (let [opts {:additional-directories ["sub"]}
          on   {:agentCapabilities {:sessionCapabilities {:additionalDirectories true}}}
          off  {:agentCapabilities {:sessionCapabilities {:additionalDirectories false}}}]
      (is (contains? (#'acp/session-params on {} opts) :additionalDirectories))
      (is (not (contains? (#'acp/session-params off {} opts) :additionalDirectories))))))

(defn- client-response
  "Run an agent->client request through the client handler and return the
  JSON-RPC message it writes back."
  [message opts]
  (let [writer (java.io.StringWriter.)]
    (#'acp/handle-request! {:writer writer} message opts)
    (json/read-str (str writer) :key-fn keyword)))

(deftest fs-methods-gated-on-their-own-capability-flags
  (testing "write is refused when only fs.readTextFile is advertised"
    (let [target   (doto (java.io.File/createTempFile "acp-test" ".txt") .delete)
          response (client-response
                    {:jsonrpc "2.0" :id 9 :method "fs/write_text_file"
                     :params {:sessionId "S1"
                              :path (.getAbsolutePath target)
                              :content "nope"}}
                    {:client-capabilities {:fs {:readTextFile true :writeTextFile false}}})]
      (is (= -32601 (get-in response [:error :code])))
      (is (not (.exists target)))))

  (testing "read is refused when only fs.writeTextFile is advertised"
    (let [response (client-response
                    {:jsonrpc "2.0" :id 9 :method "fs/read_text_file"
                     :params {:sessionId "S1" :path "/nonexistent"}}
                    {:client-capabilities {:fs {:writeTextFile true}}})]
      (is (= -32601 (get-in response [:error :code])))))

  (testing "each method is served when its own flag is advertised"
    (let [file (java.io.File/createTempFile "acp-test" ".txt")]
      (try
        (let [write (client-response
                     {:jsonrpc "2.0" :id 1 :method "fs/write_text_file"
                      :params {:sessionId "S1"
                               :path (.getAbsolutePath file)
                               :content "written"}}
                     {:client-capabilities {:fs {:writeTextFile true}}})
              read  (client-response
                     {:jsonrpc "2.0" :id 2 :method "fs/read_text_file"
                      :params {:sessionId "S1" :path (.getAbsolutePath file)}}
                     {:client-capabilities {:fs {:readTextFile true}}})]
          (is (nil? (:error write)))
          (is (= "written" (slurp file)))
          (is (= "written" (get-in read [:result :content]))))
        (finally
          (.delete file))))))

(deftest fs-read-returns-verbatim-content
  (testing "whole-file reads preserve CRLF, trailing blank lines, and the
            absence of a trailing newline"
    (let [file (java.io.File/createTempFile "acp-test" ".txt")]
      (try
        (spit file "a\r\nb\n\nno-trailing-newline")
        (let [response (client-response
                        {:jsonrpc "2.0" :id 1 :method "fs/read_text_file"
                         :params {:sessionId "S1" :path (.getAbsolutePath file)}}
                        {:client-capabilities {:fs {:readTextFile true}}})]
          (is (= "a\r\nb\n\nno-trailing-newline"
                 (get-in response [:result :content]))))
        (finally
          (.delete file))))))

(deftest fs-read-line-and-limit
  (testing "line/limit slice 1-based lines; offsets past EOF return empty
            content instead of an internal error"
    (let [file (java.io.File/createTempFile "acp-test" ".txt")]
      (try
        (spit file "one\ntwo\nthree\n")
        (let [read-req (fn [params]
                         (client-response
                          {:jsonrpc "2.0" :id 1 :method "fs/read_text_file"
                           :params (merge {:sessionId "S1"
                                           :path (.getAbsolutePath file)}
                                          params)}
                          {:client-capabilities {:fs {:readTextFile true}}}))]
          (is (= "two\n" (get-in (read-req {:line 2 :limit 1}) [:result :content])))
          (is (= "three" (get-in (read-req {:line 3}) [:result :content])))
          (is (= "" (get-in (read-req {:line 10}) [:result :content])))
          (is (nil? (:error (read-req {:line 10 :limit 5})))))
        (finally
          (.delete file))))))

(deftest mcp-server-transports-gated-on-agent-capabilities
  (testing "http/sse MCP specs require the matching mcpCapabilities flag;
            stdio needs no capability"
    (let [init-none  {:agentCapabilities {}}
          init-http  {:agentCapabilities {:mcpCapabilities {:http true}}}
          http-spec  {:type "http" :name "m" :url "https://example.com/mcp"}
          stdio-spec {:name "m" :command "mcp-server" :args []}]
      (is (thrown? clojure.lang.ExceptionInfo
                   (#'acp/session-params init-none {} {:mcp-servers [http-spec]})))
      (is (thrown? clojure.lang.ExceptionInfo
                   (#'acp/session-params init-http {} {:mcp-servers [{:type "sse" :name "m" :url "u"}]})))
      (is (= [http-spec]
             (:mcpServers (#'acp/session-params init-http {} {:mcp-servers [http-spec]}))))
      (is (= [stdio-spec]
             (:mcpServers (#'acp/session-params init-none {} {:mcp-servers [stdio-spec]})))))))

(deftest resumed-session-config-options-flow-to-set-config
  (testing "configOptions returned by session/load drive session/set_config_option"
    (let [script (str
                  "read init\n"
                  "printf '%s\\n' '{\"jsonrpc\":\"2.0\",\"id\":0,\"result\":{\"protocolVersion\":1,\"agentCapabilities\":{\"loadSession\":true}}}'\n"
                  "read load\n"
                  "printf '%s\\n' '{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"configOptions\":[{\"id\":\"model\",\"name\":\"Model\",\"category\":\"model\",\"type\":\"select\",\"currentValue\":\"old\",\"options\":[{\"value\":\"sonnet\",\"name\":\"Sonnet\"}]}]}}'\n"
                  "read config\n"
                  "printf '%s\\n' '{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"configOptions\":[]}}'\n"
                  "read prompt\n"
                  "printf '%s\\n' '{\"jsonrpc\":\"2.0\",\"method\":\"session/update\",\"params\":{\"sessionId\":\"S1\",\"update\":{\"sessionUpdate\":\"agent_message_chunk\",\"content\":{\"type\":\"text\",\"text\":\"resumed\"}}}}'\n"
                  "printf '%s\\n' '{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"stopReason\":\"end_turn\"}}'\n")
          runner (acp/acp-runner {:command ["sh" "-c" script]
                                  :timeout-ms 3000
                                  :resume "S1"
                                  :strict-config? true})
          agent  (k/agent {:name "a" :instructions "i" :model "sonnet"})
          result (k/run-agent runner agent "hi")]
      (is (k/ok? result))
      (is (= "resumed" (:text result)))
      (is (= "S1" (:session-id result))))))

(deftest stop-reason-ok-matrix
  (testing "only end_turn (or an absent stop reason) counts as success"
    (is (acp/stop-reason-ok? "end_turn"))
    (is (acp/stop-reason-ok? nil))
    (is (not (acp/stop-reason-ok? "refusal")))
    (is (not (acp/stop-reason-ok? "cancelled")))
    (is (not (acp/stop-reason-ok? "max_tokens")))
    (is (not (acp/stop-reason-ok? "max_turn_requests")))))

(deftest refusal-stop-reason-is-not-ok
  (testing "a non-end_turn stopReason surfaces as a failed result"
    (let [script (str
                  "read init\n"
                  "printf '%s\\n' '{\"jsonrpc\":\"2.0\",\"id\":0,\"result\":{\"protocolVersion\":1,\"agentCapabilities\":{}}}'\n"
                  "read session\n"
                  "printf '%s\\n' '{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"sessionId\":\"S1\"}}'\n"
                  "read prompt\n"
                  "printf '%s\\n' '{\"jsonrpc\":\"2.0\",\"method\":\"session/update\",\"params\":{\"sessionId\":\"S1\",\"update\":{\"sessionUpdate\":\"agent_message_chunk\",\"content\":{\"type\":\"text\",\"text\":\"partial\"}}}}'\n"
                  "printf '%s\\n' '{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"stopReason\":\"refusal\"}}'\n")
          runner (script-runner script)
          result (k/run-agent runner (k/agent {:name "a" :instructions "i"}) "hi")]
      (is (not (k/ok? result)))
      (is (= "refusal" (:stop-reason result)))
      (is (= "partial" (:text result)))
      (is (re-find #"refusal" (:error result))))))

(deftest non-session-update-notifications-are-not-remembered
  (testing "unrelated notifications do not append nil to :raw :updates"
    (let [script (str
                  "read init\n"
                  "printf '%s\\n' '{\"jsonrpc\":\"2.0\",\"id\":0,\"result\":{\"protocolVersion\":1,\"agentCapabilities\":{}}}'\n"
                  "read session\n"
                  "printf '%s\\n' '{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"sessionId\":\"S1\"}}'\n"
                  "read prompt\n"
                  "printf '%s\\n' '{\"jsonrpc\":\"2.0\",\"method\":\"other/notification\",\"params\":{\"detail\":\"ignored\"}}'\n"
                  "printf '%s\\n' '{\"jsonrpc\":\"2.0\",\"method\":\"session/update\",\"params\":{\"sessionId\":\"S1\",\"update\":{\"sessionUpdate\":\"agent_message_chunk\",\"content\":{\"type\":\"text\",\"text\":\"hi\"}}}}'\n"
                  "printf '%s\\n' '{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"stopReason\":\"end_turn\"}}'\n")
          runner (script-runner script)
          result (k/run-agent runner (k/agent {:name "a" :instructions "i"}) "hi")]
      (is (k/ok? result))
      (is (= "hi" (:text result)))
      (is (= 1 (count (get-in result [:raw :updates]))))
      (is (every? some? (get-in result [:raw :updates]))))))

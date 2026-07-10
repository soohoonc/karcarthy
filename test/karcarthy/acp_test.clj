(ns karcarthy.acp-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [karcarthy :as k]
            [karcarthy.acp :as acp]
            [karcarthy.mcp-test :as mcp-test])
  (:import [java.io BufferedReader BufferedWriter InputStreamReader
            OutputStreamWriter]
           [java.net InetAddress ServerSocket Socket]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files Path]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-directory []
  (Files/createTempDirectory "karcarthy-acp-"
                             (make-array FileAttribute 0)))

(defn- delete-tree! [^Path root]
  (doseq [file (reverse (file-seq (.toFile root)))]
    (Files/deleteIfExists (.toPath file))))

(defn- write-json! [^BufferedWriter writer value]
  (.write writer (json/write-str value))
  (.newLine writer)
  (.flush writer))

(defn- read-json! [^BufferedReader reader]
  (let [result (future (.readLine reader))
        line (deref result 10000 ::timeout)]
    (when (= ::timeout line)
      (future-cancel result)
      (throw (ex-info "Timed out waiting for ACP output" {})))
    (when-not line
      (throw (ex-info "ACP output closed" {})))
    (json/read-str line :key-fn keyword)))

(defn- request! [writer reader id method params]
  (write-json! writer {:jsonrpc "2.0"
                       :id id
                       :method method
                       :params params})
  (loop []
    (let [message (read-json! reader)]
      (if (= id (:id message)) message (recur)))))

(defn- agent-factory [{:keys [mcp-tools]}]
  (let [turn (atom 0)
        remote-tool (:name (first mcp-tools))]
    (k/agent
     {:name "acp-test-agent"
      :model
      {:id "fake"
       :transport
       {:stream
        (fn [_ emit!]
          (if (= 1 (swap! turn inc))
            {:type :tool-calls
             :calls [{:id "call_1"
                      :name remote-tool
                      :input {:text "hello from ACP"}}]}
            (do
              (emit! {:type :text-delta :delta "MCP "})
              (emit! {:type :text-delta :delta "completed"})
              {:type :final :output "MCP completed"})))}}
      :instructions "Exercise the session MCP tool."
      :tools mcp-tools
      :output string?})))

(deftest serves-acp-v1-and-bridges-session-mcp-tools
  (let [root (temp-directory)
        server-socket (ServerSocket. 0 1 (InetAddress/getLoopbackAddress))
        server-thread
        (Thread/startVirtualThread
         ^Runnable
         #(with-open [socket (.accept server-socket)]
            (acp/serve! {:agent agent-factory
                         :in (.getInputStream socket)
                         :out (.getOutputStream socket)
                         :client-request-timeout-ms 10000})))
        client (Socket. (InetAddress/getLoopbackAddress)
                        (.getLocalPort server-socket))
        writer (BufferedWriter.
                (OutputStreamWriter. (.getOutputStream client)
                                     StandardCharsets/UTF_8))
        reader (BufferedReader.
                (InputStreamReader. (.getInputStream client)
                                    StandardCharsets/UTF_8))]
    (try
      (let [initialized
            (request! writer reader 1 "initialize" {:protocolVersion 1})]
        (is (= 1 (get-in initialized [:result :protocolVersion])))
        (is (= false
               (get-in initialized
                       [:result :agentCapabilities :mcpCapabilities :http]))))

      (let [created
            (request! writer reader 2 "session/new"
                      {:cwd (str root)
                       :mcpServers [(mcp-test/fixture-command)]})
            session-id (get-in created [:result :sessionId])]
        (is (string? session-id))
        (write-json! writer {:jsonrpc "2.0"
                             :id 3
                             :method "session/prompt"
                             :params {:sessionId session-id
                                      :prompt [{:type "text"
                                                :text "Use the echo tool."}]}})
        (loop [updates []
               permission? false]
          (let [message (read-json! reader)]
            (cond
              (= "session/request_permission" (:method message))
              (do
                (write-json! writer
                             {:jsonrpc "2.0"
                              :id (:id message)
                              :result
                              {:outcome {:outcome "selected"
                                         :optionId "allow-once"}}})
                (recur updates true))

              (= "session/update" (:method message))
              (recur (conj updates (get-in message [:params :update]))
                     permission?)

              (= 3 (:id message))
              (do
                (is permission?)
                (is (= "end_turn" (get-in message [:result :stopReason])))
                (is (some #(= "tool_call" (:sessionUpdate %)) updates))
                (is (some #(and (= "tool_call_update" (:sessionUpdate %))
                                (= "completed" (:status %)))
                          updates))
                (is (= "MCP completed"
                       (->> updates
                            (filter #(= "agent_message_chunk"
                                        (:sessionUpdate %)))
                            (map #(get-in % [:content :text]))
                            (apply str)))))

              :else (recur updates permission?))))

        (is (= {}
               (:result (request! writer reader 4 "session/close"
                                  {:sessionId session-id})))))
      (finally
        (.close client)
        (.close server-socket)
        (.join server-thread 10000)
        (delete-tree! root)))))

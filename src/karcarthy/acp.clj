(ns karcarthy.acp
  "ACP v1 stdio server for exposing karcarthy Agents to editors and Harbor."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [karcarthy.agent :as agent]
            [karcarthy.schema :as schema]
            [karcarthy.mcp :as mcp]
            [karcarthy.run :as run]
            [karcarthy.session :as session]
            [karcarthy.tool :as tool])
  (:import [java.io BufferedReader BufferedWriter InputStreamReader OutputStreamWriter]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files Paths LinkOption]
           [java.util UUID]))

(def protocol-version 1)
(def ^:private no-link-options (make-array LinkOption 0))

(defn- write-message! [server message]
  (let [^BufferedWriter writer (:writer server)]
    (locking writer
      (.write writer (json/write-str message))
      (.newLine writer)
      (.flush writer))))

(defn- respond! [server id result]
  (write-message! server {:jsonrpc "2.0" :id id :result result}))

(defn- error! [server id code message data]
  (write-message! server
                  {:jsonrpc "2.0"
                   :id id
                   :error (cond-> {:code code :message message}
                            data (assoc :data data))}))

(defn- notify! [server method params]
  (write-message! server {:jsonrpc "2.0" :method method :params params}))

(defn- session-update! [server session-id update]
  (notify! server "session/update"
           {:sessionId session-id :update update}))

(defn- request-client! [server session-id method params]
  (let [id (str "karcarthy_" (swap! (:next-request-id server) inc))
        response (promise)]
    (swap! (:pending-client-requests server)
           assoc id {:response response :session-id session-id})
    (write-message! server {:jsonrpc "2.0" :id id :method method :params params})
    (let [message (deref response (:client-request-timeout-ms server) ::timeout)]
      (swap! (:pending-client-requests server) dissoc id)
      (cond
        (= ::timeout message)
        (schema/fail! :acp :timeout "ACP client request timed out"
                    {:method method :session-id session-id})
        (:error message)
        (schema/fail! :acp :client-request
                    (or (get-in message [:error :message])
                        "ACP client request failed")
                    {:method method :error (:error message)})
        :else (:result message)))))

(defn- close-session! [session]
  (reset! (:cancel session) true)
  (doseq [connection (:mcp-connections session)]
    (mcp/close! connection))
  true)

(defn- require-initialized! [server]
  (when-not @(:initialized? server)
    (schema/fail! :acp :initialize
                "ACP initialize must complete before session methods")))

(defn- get-session [server session-id]
  (or (get @(:sessions server) session-id)
      (schema/fail! :acp :session "Unknown ACP session"
                  {:session-id session-id})))

(defn- normalize-cwd [value]
  (let [path (-> (Paths/get (str value) (make-array String 0))
                 (.toAbsolutePath)
                 (.normalize))]
    (when-not (Files/isDirectory path no-link-options)
      (schema/fail! :acp :session "ACP session cwd must be a directory"
                  {:cwd (str path)}))
    (str path)))

(defn- connect-mcp-servers! [specs cwd]
  (loop [remaining (vec (or specs []))
         connections []]
    (if-let [spec (first remaining)]
      (if (:command spec)
        (let [connection
              (try
                (mcp/connect! (assoc spec :cwd cwd))
                (catch Throwable error
                  (doseq [open-connection connections]
                    (mcp/close! open-connection))
                  (throw error)))]
          (recur (subvec remaining 1) (conj connections connection)))
        (do
          (doseq [connection connections] (mcp/close! connection))
          (schema/fail! :acp :mcp
                      "This build supports ACP-provided stdio MCP servers only"
                      {:server (dissoc spec :headers :env)})))
      connections)))

(defn- merge-tools [left right]
  (let [key-fn (fn [tool]
                 (if (tool/tool? tool)
                   [:function (:name tool)]
                   [:hosted (:transport tool) (:spec tool)]))]
    (->> (concat left right)
         (reduce (fn [{:keys [seen result]} tool]
                   (let [key (key-fn tool)]
                     (if (contains? seen key)
                       {:seen seen :result result}
                       {:seen (conj seen key) :result (conj result tool)})))
                 {:seen #{} :result []})
         :result)))

(defn- configured-model-id [agent]
  (let [model (:model agent)]
    (when (and (map? model) (string? (:id model)) (seq (:id model)))
      (:id model))))

(defn- session-model-ids [server]
  (let [source (:agent server)
        default-id (when (agent/agent? source) (configured-model-id source))]
    (vec (distinct (remove nil? (concat [default-id] (:models server)))))))

(defn- model-config-option [session]
  (when-let [current @(:model-id session)]
    {:id "model"
     :name "Model"
     :description "Model used by this Agent session."
     :category "model"
     :type "select"
     :currentValue current
     :options (mapv (fn [model-id] {:value model-id :name model-id})
                    (:model-ids session))}))

(defn- session-config-options [session]
  (cond-> []
    @(:model-id session) (conj (model-config-option session))))

(defn- set-session-model! [session model-id]
  (when-not (and (string? model-id)
                 (contains? (set (:model-ids session)) model-id))
    (schema/fail! :acp :configuration
                "Requested model is not available for this ACP session"
                {:model-id model-id :available-models (:model-ids session)}))
  (when @(:running? session)
    (schema/fail! :acp :session
                "Cannot change the model during an active prompt"
                {:session-id (:id session)}))
  (reset! (:model-id session) model-id)
  (session-config-options session))

(defn- materialize-agent [server session]
  (let [source (:agent server)
        context {:cwd (:cwd session)
                 :mcp-tools (:mcp-tools session)
                 :session-id (:id session)
                 :model-id @(:model-id session)}
        agent (if (agent/agent? source) source (source context))
        agent (if (and @(:model-id session)
                       (map? (:model agent)))
                (assoc-in agent [:model :id] @(:model-id session))
                agent)]
    (when-not (agent/agent? agent)
      (schema/fail! :acp :configuration
                  "ACP :agent must be an Agent or a function returning one"
                  {:value agent}))
    (update agent :tools
            #(merge-tools (or % []) (:mcp-tools session)))))

(defn- prompt-text [blocks]
  (->> blocks
       (map (fn [block]
              (case (:type block)
                "text" (:text block)
                "resource_link" (str "Resource: " (:uri block))
                "resource"
                (let [resource (:resource block)]
                  (str "Resource " (:uri resource) "\n"
                       (or (:text resource)
                           (json/write-str resource))))
                (json/write-str block))))
       (remove str/blank?)
       (str/join "\n\n")))

(defn- tool-kind [name]
  (cond
    (= name "read") "read"
    (= name "write") "edit"
    (= name "edit") "edit"
    (= name "search") "search"
    (= name "bash") "execute"
    (re-find #"(?i)(search|grep)" name) "search"
    (re-find #"(?i)(fetch|http|web)" name) "fetch"
    :else "other"))

(defn- tool-output-content [output]
  (let [blocks (if (and (map? output) (sequential? (:content output)))
                 (:content output)
                 [output])]
    (->> blocks
         (remove nil?)
         (mapv (fn [block]
                 {:type "content"
                  :content (if (and (map? block) (string? (:type block)))
                             block
                             {:type "text"
                              :text (if (string? block)
                                      block
                                      (json/write-str block))})})))))

(defn- event-handler [server session message-id streamed?]
  (fn [event]
    (case (:type event)
      :model/text-delta
      (do
        (reset! streamed? true)
        (session-update!
         server (:id session)
         {:sessionUpdate "agent_message_chunk"
          :messageId message-id
          :content {:type "text" :text (:delta event)}}))

      :tool/started
      (session-update!
       server (:id session)
       {:sessionUpdate "tool_call"
        :toolCallId (:tool-call-id event)
        :title (:tool event)
        :kind (tool-kind (:tool event))
        :status "in_progress"
        :rawInput (or (:input event) {})})

      :tool/completed
      (let [output (:output event)]
        (session-update!
         server (:id session)
         (cond-> {:sessionUpdate "tool_call_update"
                  :toolCallId (:tool-call-id event)
                  :status "completed"
                  :rawOutput (if (map? output)
                               output
                               {:output output})}
           (some? output) (assoc :content (tool-output-content output)))))

      :tool/failed
      (let [error (:error event)]
        (session-update!
         server (:id session)
         (cond-> {:sessionUpdate "tool_call_update"
                  :toolCallId (:tool-call-id event)
                  :status "failed"
                  :rawOutput {:error error}}
           (some? error) (assoc :content (tool-output-content error)))))
      nil)))

(defn- combine-event-handlers [& callbacks]
  (let [callbacks (remove nil? callbacks)]
    (fn [event]
      (doseq [callback callbacks]
        (try (callback event) (catch Throwable _ nil))))))

(defn- approval-handler [server session]
  (fn [{:keys [tool input]}]
    (if (contains? @(:always-allowed session) (:name tool))
      true
      (let [tool-call-id (str "permission_" (UUID/randomUUID))
            result
            (request-client!
             server (:id session) "session/request_permission"
             {:sessionId (:id session)
              :toolCall {:toolCallId tool-call-id
                         :title (str "Allow " (:name tool))
                         :kind (tool-kind (:name tool))
                         :status "pending"
                         :rawInput (or input {})}
              :options [{:optionId "allow-once"
                         :name "Allow once"
                         :kind "allow_once"}
                        {:optionId "allow-always"
                         :name "Always allow this tool"
                         :kind "allow_always"}
                        {:optionId "reject-once"
                         :name "Reject"
                         :kind "reject_once"}]})
            outcome (:outcome result)
            option-id (:optionId outcome)]
        (when (= "allow-always" option-id)
          (swap! (:always-allowed session) conj (:name tool)))
        (and (= "selected" (:outcome outcome))
             (contains? #{"allow-once" "allow-always"} option-id))))))

(defn- prompt-usage [run]
  (let [model-usages (->> (:events run)
                          (filter #(= :model/completed (:type %)))
                          (map :usage))
        token-count (fn [usage kebab snake]
                      (or (get usage kebab) (get usage snake)))]
    (when (and (seq model-usages)
               (every? #(and (integer? (token-count % :input-tokens
                                                     :input_tokens))
                             (integer? (token-count % :output-tokens
                                                     :output_tokens)))
                       model-usages))
      (let [input-tokens (long (get-in run [:usage :input-tokens]))
            output-tokens (long (get-in run [:usage :output-tokens]))]
        {:inputTokens input-tokens
         :outputTokens output-tokens
         :totalTokens (+ input-tokens output-tokens)}))))

(defn- run-prompt! [server request]
  (let [id (:id request)
        {:keys [sessionId prompt]} (:params request)
        session (get-session server sessionId)]
    (if-not (compare-and-set! (:running? session) false true)
      (error! server id -32000 "Session already has an active prompt" nil)
      (try
        (reset! (:cancel session) false)
        (let [message-id (str "msg_" (UUID/randomUUID))
              streamed? (atom false)
              agent (materialize-agent server session)
              base-options (or (:run-options server) {})
              run-options
              (merge base-options
                     {:session (:agent-session session)
                      :cancel (:cancel session)
                      :on-event (combine-event-handlers
                                 (:on-event base-options)
                                 (event-handler server session message-id
                                                streamed?))
                      :approval (approval-handler server session)
                      :context (merge (:context base-options)
                                      {:cwd (:cwd session)
                                       :session-id sessionId})})
              run (run/run! agent (prompt-text prompt) run-options)
              text (if (= :completed (:status run))
                     (if (string? (:output run))
                       (:output run)
                       (json/write-str (:output run)))
                     (str "karcarthy run failed: "
                          (get-in run [:error :message])))
              stop-reason (cond
                            (= :cancelled (:status run)) "cancelled"
                            (and (= :budget (get-in run [:error :kind]))
                                 (contains? #{:model-loop :run}
                                            (get-in run [:error :phase])))
                            "max_turn_requests"
                            :else "end_turn")
              usage (prompt-usage run)]
          (when (or (not @streamed?) (not= :completed (:status run)))
            (session-update!
             server sessionId
             {:sessionUpdate "agent_message_chunk"
              :messageId message-id
              :content {:type "text" :text text}}))
          (respond! server id
                    (cond-> {:stopReason stop-reason}
                      usage (assoc :usage usage))))
        (catch Throwable error
          (error! server id -32000
                  (or (ex-message error) "ACP prompt failed")
                  (schema/throwable->failure error)))
        (finally
          (reset! (:running? session) false))))))

(defn- handle-request! [server request]
  (let [id (:id request)
        method (:method request)
        params (:params request)]
    (try
      (case method
        "initialize"
        (let [requested (:protocolVersion params)]
          (reset! (:initialized? server) true)
          (respond!
           server id
           {:protocolVersion (if (= protocol-version requested)
                               protocol-version
                               protocol-version)
            :agentCapabilities
            {:promptCapabilities {:embeddedContext true}
             :mcpCapabilities {:http false :sse false}
             :sessionCapabilities {:close {}}}
            :agentInfo {:name "karcarthy"
                        :title "karcarthy"
                        :version "0.0.2"}
            :authMethods []}))

        "session/new"
        (do
          (require-initialized! server)
          (let [cwd (normalize-cwd (:cwd params))
                connections (connect-mcp-servers! (:mcpServers params) cwd)
                mcp-tools (mapcat #(mcp/tools % {:needs-approval :always})
                                  connections)
                session-id (str "sess_" (UUID/randomUUID))
                model-ids (session-model-ids server)
                session {:id session-id
                         :cwd cwd
                         :mcp-connections connections
                         :mcp-tools (vec mcp-tools)
                         :agent-session (session/memory-session {:id session-id})
                         :cancel (atom false)
                         :running? (atom false)
                         :model-ids model-ids
                         :model-id (atom (first model-ids))
                         :always-allowed (atom #{})}]
            (swap! (:sessions server) assoc session-id session)
            (respond! server id
                      (cond-> {:sessionId session-id}
                        (seq model-ids)
                        (assoc :configOptions
                               (session-config-options session))))))

        "session/set_config_option"
        (let [session (get-session server (:sessionId params))]
          (if-not (= "model" (:configId params))
            (error! server id -32602 "Unknown session configuration option"
                    {:configId (:configId params)})
            (respond! server id
                      {:configOptions
                       (set-session-model! session (:value params))})))

        ;; Harbor's current ACP adapter still calls the pre-config-options
        ;; model method. Keep this narrow compatibility alias at the boundary;
        ;; `session/set_config_option` above is the canonical ACP interface.
        "session/set_model"
        (let [session (get-session server (:sessionId params))]
          (set-session-model! session (:modelId params))
          (respond! server id {}))

        "session/prompt"
        (do
          (require-initialized! server)
          (run-prompt! server request))

        "session/cancel"
        (let [session (get-session server (:sessionId params))]
          (reset! (:cancel session) true)
          (doseq [[_ pending] @(:pending-client-requests server)
                  :when (= (:id session) (:session-id pending))]
            (deliver (:response pending)
                     {:result {:outcome {:outcome "cancelled"}}}))
          (when (some? id) (respond! server id {})))

        "session/close"
        (let [session (get-session server (:sessionId params))]
          (close-session! session)
          (swap! (:sessions server) dissoc (:id session))
          (respond! server id {}))

        (error! server id -32601 "Method not found" {:method method}))
      (catch Throwable error
        (error! server id -32000
                (or (ex-message error) "ACP request failed")
                (schema/throwable->failure error))))))

(defn serve!
  "Serve an Agent or session Agent factory over ACP v1 JSON-RPC on stdio.

  A factory receives `{:cwd string :mcp-tools [...] :session-id string
  :model-id string-or-nil}` and must return an Agent. This is the preferred
  form for coding Agents because their local tools are rooted per ACP session.

  `:models` may contain selectable model-id strings. For a static model
  Agent, its configured model is included automatically."
  [{:keys [agent in out client-request-timeout-ms] :as options}]
  (when-not agent
    (schema/fail! :schema :configuration "ACP serve! requires :agent"))
  (let [reader (BufferedReader.
                (InputStreamReader. (or in System/in) StandardCharsets/UTF_8))
        writer (BufferedWriter.
                (OutputStreamWriter. (or out System/out) StandardCharsets/UTF_8))
        models (:models options)
        _ (when-not (or (nil? models)
                        (and (sequential? models)
                             (every? #(and (string? %) (seq %)) models)))
            (schema/fail! :schema :configuration
                        "ACP :models must be a sequence of non-empty strings"
                        {:value models}))
        server {:agent agent
                :models (vec (distinct (or models [])))
                :run-options (:run-options options)
                :reader reader
                :writer writer
                :initialized? (atom false)
                :sessions (atom {})
                :pending-client-requests (atom {})
                :next-request-id (atom 0)
                :client-request-timeout-ms
                (long (or client-request-timeout-ms 300000))}]
    (try
      (loop []
        (when-let [line (.readLine reader)]
          (when-not (str/blank? line)
            (let [message (json/read-str line :key-fn keyword)]
              (if (and (:id message) (nil? (:method message)))
                (when-let [pending (get @(:pending-client-requests server)
                                        (str (:id message)))]
                  (deliver (:response pending) message))
                (Thread/startVirtualThread
                 ^Runnable #(handle-request! server message)))))
          (recur)))
      (finally
        (doseq [[_ session] @(:sessions server)]
          (close-session! session))))))

(defn serve-var!
  "Resolve `namespace/var` and serve its Agent value or Agent factory."
  [qualified-symbol]
  (let [qualified (symbol qualified-symbol)
        namespace-symbol (some-> qualified namespace symbol)]
    (when-not namespace-symbol
      (schema/fail! :schema :configuration
                  "ACP Agent var must be namespace/var"
                  {:value qualified-symbol}))
    (require namespace-symbol)
    (let [value (some-> (ns-resolve namespace-symbol
                                    (symbol (name qualified)))
                        deref)]
      (when-not value
        (schema/fail! :schema :configuration "ACP Agent var was not found"
                    {:value qualified-symbol}))
      (serve! {:agent value}))))

(defn -main [& [qualified-symbol]]
  (if qualified-symbol
    (serve-var! qualified-symbol)
    (do
      (binding [*out* *err*]
        (println "Usage: clojure -M -m karcarthy.acp namespace/agent-var"))
      (System/exit 2))))

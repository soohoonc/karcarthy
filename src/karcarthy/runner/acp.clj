(ns karcarthy.runner.acp
  "Runner for Agent Client Protocol (ACP) agents over stdio JSON-RPC.

  The runner launches an ACP agent process, initializes the protocol, creates or
  resumes a session, sends one prompt turn, collects `agent_message_chunk`
  updates, and returns a karcarthy result map."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [karcarthy.core :as k])
  (:import [java.util.concurrent TimeUnit]))

(def protocol-version 1)

(defn request
  "Build a JSON-RPC request map."
  [id method params]
  {:jsonrpc "2.0"
   :id      id
   :method  method
   :params  params})

(defn success
  "Build a JSON-RPC success response."
  [id result]
  {:jsonrpc "2.0"
   :id      id
   :result  result})

(defn error
  "Build a JSON-RPC error response."
  [id code message]
  {:jsonrpc "2.0"
   :id      id
   :error   {:code code :message message}})

(defn prompt-text
  "Build the text sent in `session/prompt` for one karcarthy agent invocation."
  [agent input {:keys [include-agent-instructions?]
                :or   {include-agent-instructions? true}}]
  (if-not include-agent-instructions?
    (str input)
    (str "Karcarthy agent: " (:name agent)
         (when-let [description (not-empty (:description agent))]
           (str "\n\nDescription:\n" description))
         "\n\nInstructions:\n" (:instructions agent)
         "\n\nUser input:\n" input)))

(defn prompt-params
  "Build ACP `session/prompt` params for a karcarthy agent and input."
  [session-id agent input opts]
  (let [text (prompt-text agent input opts)]
    (cond-> {:sessionId session-id
             :prompt    [{:type "text" :text text}]}
      true
      (assoc :_meta
             {:karcarthy.dev/agent
              (cond-> {:name (:name agent)}
                (:model agent)  (assoc :model (:model agent))
                (:tools agent)  (assoc :tools (:tools agent)))}))))

(defn- absolute-path [path]
  (.getAbsolutePath (io/file (or path "."))))

(defn- start-process [{:keys [command cwd dir env]}]
  (when-not (and (sequential? command) (seq command))
    (throw (ex-info "acp-runner requires :command as an argv vector"
                    {:command command})))
  (let [pb (ProcessBuilder. ^java.util.List (vec command))]
    (.directory pb (io/file (absolute-path (or cwd dir "."))))
    (when (seq env)
      (let [e (.environment pb)]
        (doseq [[k v] env]
          (.put e (str k) (str v)))))
    (let [process (.start pb)]
      {:process process
       :reader  (io/reader (.getInputStream process) :encoding "UTF-8")
       :writer  (io/writer (.getOutputStream process) :encoding "UTF-8")
       :stderr  (future (slurp (.getErrorStream process)))})))

(defn- close-process! [{:keys [process reader writer]}]
  (try (.close ^java.io.Writer writer) (catch Throwable _ nil))
  (try (.close ^java.io.Reader reader) (catch Throwable _ nil))
  (when (.isAlive ^Process process)
    (.destroy ^Process process)
    (when-not (.waitFor ^Process process 200 TimeUnit/MILLISECONDS)
      (.destroyForcibly ^Process process))))

(defn- stderr [{:keys [stderr]}]
  (try
    (deref stderr 100 "")
    (catch Throwable _ "")))

(defn- send! [{:keys [writer]} message]
  (.write ^java.io.Writer writer (json/write-str message))
  (.write ^java.io.Writer writer "\n")
  (.flush ^java.io.Writer writer))

(defn- read-line! [{:keys [reader process]} timeout-ms]
  (let [line-f (future (.readLine ^java.io.BufferedReader reader))
        line   (if timeout-ms
                 (deref line-f timeout-ms ::timeout)
                 @line-f)]
    (when (= ::timeout line)
      (.destroyForcibly ^Process process)
      (throw (ex-info "ACP agent timed out waiting for a message"
                      {:timeout-ms timeout-ms})))
    (when-not line
      (throw (ex-info "ACP agent closed stdout before responding" {})))
    line))

(defn- read-message! [conn timeout-ms]
  (json/read-str (read-line! conn timeout-ms) :key-fn keyword))

(defn- response? [message]
  (and (contains? message :id)
       (or (contains? message :result)
           (contains? message :error))
       (not (contains? message :method))))

(defn- request? [message]
  (and (contains? message :id)
       (contains? message :method)))

(defn- notification? [message]
  (and (contains? message :method)
       (not (contains? message :id))))

(defn- selected [option]
  {:outcome {:outcome  "selected"
             :optionId (:optionId option)}})

(defn- permission-outcome [message opts]
  (let [handler (:permission-handler opts)
        policy  (or (:permission-policy opts) :reject)
        options (get-in message [:params :options])]
    (cond
      handler
      (let [outcome (handler message)]
        (cond
          (string? outcome) {:outcome {:outcome "selected" :optionId outcome}}
          (keyword? outcome) {:outcome {:outcome "selected" :optionId (name outcome)}}
          (map? outcome) outcome
          :else {:outcome {:outcome "cancelled"}}))

      (= :allow policy)
      (if-let [option (or (first (filter #(str/starts-with? (:kind %) "allow") options))
                          (first options))]
        (selected option)
        {:outcome {:outcome "cancelled"}})

      (= :cancel policy)
      {:outcome {:outcome "cancelled"}}

      :else
      (if-let [option (first (filter #(str/starts-with? (:kind %) "reject") options))]
        (selected option)
        {:outcome {:outcome "cancelled"}}))))

(defn- handle-fs-request [message]
  (let [{:keys [path line limit content]} (:params message)]
    (case (:method message)
      "fs/read_text_file"
      (let [text  (slurp path)
            lines (str/split-lines text)
            start (max 0 (dec (or line 1)))
            end   (if limit (min (count lines) (+ start limit)) (count lines))]
        {:result {:content (str (str/join "\n" (subvec (vec lines) start end))
                                (when (and (seq lines) (or (nil? limit)
                                                           (< end (count lines))))
                                  "\n"))}})

      "fs/write_text_file"
      (do
        (spit path content)
        {:result nil}))))

(defn- handle-request! [conn message opts]
  (let [id (:id message)]
    (try
      (cond
        (= "session/request_permission" (:method message))
        (send! conn (success id (permission-outcome message opts)))

        (and (contains? #{"fs/read_text_file" "fs/write_text_file"} (:method message))
             (get-in opts [:client-capabilities :fs]))
        (let [{:keys [result]} (handle-fs-request message)]
          (send! conn (success id result)))

        (:request-handler opts)
        (let [reply ((:request-handler opts) message)]
          (send! conn (if (and (map? reply) (contains? reply :error))
                        (assoc reply :jsonrpc "2.0" :id id)
                        (success id reply))))

        :else
        (send! conn (error id -32601 "Method not found")))
      (catch Throwable t
        (send! conn (error id -32603 (or (ex-message t) (str t))))))))

(defn- observe-message! [message opts]
  (when-let [on-event (:on-event opts)]
    (try (on-event message) (catch Throwable _ nil))))

(defn- remember-update! [state message]
  (let [session-update (get-in message [:params :update])]
    (swap! state update :updates conj session-update)
    (when (and (= "session/update" (:method message))
               (= "agent_message_chunk" (:sessionUpdate session-update)))
      (when-let [text (get-in session-update [:content :text])]
        (swap! state update :text conj text)))))

(defn- wait-response! [conn id state opts]
  (if-let [message (get-in @state [:responses id])]
    (do
      (swap! state update :responses dissoc id)
      message)
    (loop []
      (let [message (read-message! conn (:timeout-ms opts))]
        (observe-message! message opts)
        (cond
          (response? message)
          (if (= id (:id message))
            message
            (do
              (swap! state assoc-in [:responses (:id message)] message)
              (recur)))

          (request? message)
          (do
            (handle-request! conn message opts)
            (recur))

          (notification? message)
          (do
            (remember-update! state message)
            (recur))

          :else
          (recur))))))

(defn- request! [conn ids state method params opts]
  (let [id @ids]
    (swap! ids inc)
    (send! conn (request id method params))
    (let [response (wait-response! conn id state opts)]
      (if-let [error (:error response)]
        (throw (ex-info (str "ACP " method " failed: " (:message error))
                        {:method method :error error}))
        (:result response)))))

(defn- initialize! [conn ids state opts]
  (let [params {:protocolVersion    protocol-version
                :clientCapabilities (or (:client-capabilities opts) {})
                :clientInfo         (merge {:name    "karcarthy"
                                            :title   "karcarthy"
                                            :version "0.0.0"}
                                           (:client-info opts))}
        result (request! conn ids state "initialize" params opts)]
    (when-not (= protocol-version (:protocolVersion result))
      (throw (ex-info "ACP protocol version mismatch"
                      {:requested protocol-version
                       :returned  (:protocolVersion result)})))
    result))

(defn- session-capability? [init capability]
  (contains? (or (get-in init [:agentCapabilities :sessionCapabilities]) {})
             capability))

(defn- session-params [init agent opts]
  (cond-> {:cwd        (absolute-path (or (:cwd opts) (:dir opts) "."))
           :mcpServers (vec (or (:mcp-servers opts)
                                (get-in agent [:config :mcp-servers])
                                []))}
    (and (:additional-directories opts)
         (session-capability? init :additionalDirectories))
    (assoc :additionalDirectories
           (mapv absolute-path (:additional-directories opts)))))

(defn- session! [conn ids state init agent opts]
  (let [resume (:resume opts)
        params (session-params init agent opts)]
    (cond
      resume
      (cond
        (session-capability? init :resume)
        (do
          (request! conn ids state "session/resume"
                    (assoc params :sessionId resume)
                    opts)
          {:sessionId resume})

        (true? (get-in init [:agentCapabilities :loadSession]))
        (do
          (request! conn ids state "session/load"
                    (assoc params :sessionId resume)
                    opts)
          {:sessionId resume})

        :else
        (throw (ex-info "ACP agent does not support session resume/load"
                        {:session-id resume
                         :agent-capabilities (:agentCapabilities init)})))

      :else
      (request! conn ids state "session/new" params opts))))

(defn- config-options [session-result]
  (vec (:configOptions session-result)))

(defn- config-option [options id]
  (let [id (name id)]
    (or (first (filter #(= id (:id %)) options))
        (first (filter #(= id (:category %)) options)))))

(defn- desired-config [agent opts]
  (let [explicit (merge (get-in agent [:config :acp :config-options])
                        (:config-options opts))]
    (cond-> explicit
      (and (:model agent) (not (contains? explicit :model)) (not (contains? explicit "model")))
      (assoc "model" (:model agent)))))

(defn- set-config! [conn ids state session-id session-result agent opts]
  (let [options (config-options session-result)]
    (doseq [[id value] (desired-config agent opts)]
      (if-let [option (config-option options id)]
        (request! conn ids state "session/set_config_option"
                  {:sessionId session-id
                   :configId  (:id option)
                   :value     (str value)}
                  opts)
        (when (:strict-config? opts)
          (throw (ex-info "ACP agent did not advertise requested config option"
                          {:config-id id
                           :available (mapv :id options)})))))))

(defn- run-acp! [agent input opts]
  (let [conn  (start-process opts)
        ids   (atom 0)
        state (atom {:responses {} :updates [] :text []})]
    (try
      (let [init           (initialize! conn ids state opts)
            session-result (session! conn ids state init agent opts)
            session-id     (:sessionId session-result)]
        (set-config! conn ids state session-id session-result agent opts)
        (let [prompt-result (request! conn ids state "session/prompt"
                                      (prompt-params session-id agent input opts)
                                      opts)
              text          (str/join "" (:text @state))]
          (k/result {:agent      (:name agent)
                     :text       text
                     :session-id session-id
                     :stop-reason (:stopReason prompt-result)
                     :raw        {:runner :acp
                                  :session-id session-id
                                  :stop-reason (:stopReason prompt-result)
                                  :agent-info (:agentInfo init)
                                  :updates (:updates @state)
                                  :stderr (stderr conn)}})))
      (finally
        (close-process! conn)))))

(defn acp-runner
  "Build a runner for an ACP-compliant agent process.

    (acp-runner {:command [\"some-acp-agent\"]
                 :cwd \"/path/to/project\"
                 :mcp-servers [...]
                 :permission-policy :reject})

  The runner speaks ACP over stdio. It does not make `k/agent` a native ACP
  agent; the ACP agent is the subprocess. Karcarthy folds the agent's
  instructions into the prompt turn.

  Common options:
    :command                  argv vector for the ACP agent process
    :cwd / :dir               working directory for the ACP session
    :mcp-servers              ACP MCP server specs
    :client-capabilities      advertised ACP client capabilities
    :config-options           map of ACP session config option ids/categories to values
    :strict-config?           throw when a requested config option is unavailable
    :permission-policy        :reject (default), :allow, or :cancel
    :permission-handler       fn of the permission request -> option id or ACP outcome map
    :request-handler          fn for custom ACP agent->client requests
    :on-event                 fn called with every ACP JSON-RPC message received
    :timeout-ms               max wait per ACP message"
  ([config]
   (reify k/Runner
     (-run [_ agent input opts]
       (let [opts (merge config opts)]
         (try
           (run-acp! agent input opts)
           (catch Throwable t
             (k/result {:agent (:name agent)
                        :ok? false
                        :text nil
                        :error (or (ex-message t) (str t))
                        :raw {:runner :acp
                              :exception (.getName (class t))}})))))))
  ([command opts]
   (acp-runner (assoc opts :command command))))

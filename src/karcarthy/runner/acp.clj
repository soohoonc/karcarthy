(ns karcarthy.runner.acp
  "Runner for Agent Client Protocol (ACP) agents over stdio JSON-RPC.

  The runner launches an ACP agent process, initializes the protocol, creates or
  resumes a session, sends one prompt turn, collects `agent_message_chunk`
  updates, and returns a karcarthy result map."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [karcarthy.core :as k]
            [karcarthy.terminal :as terminal])
  (:import [java.util.concurrent TimeUnit]))

(def protocol-version
  "The ACP protocol version this client requests during `initialize`. The
  runner throws when the agent answers with a different version."
  1)

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
  (let [extra (:prompt-content opts)]
    (when (and (some? extra) (not (and (vector? extra) (every? map? extra))))
      (throw (ex-info ":prompt-content must be a vector of ACP content maps"
                      {:prompt-content extra})))
    {:sessionId session-id
     :prompt    (into [{:type "text" :text (prompt-text agent input opts)}]
                      extra)
     :_meta     {:karcarthy.dev/agent
                 (cond-> {:name (:name agent)}
                   (:model agent) (assoc :model (:model agent))
                   (:tools agent) (assoc :tools (:tools agent)))}}))

(defn- check-prompt-content! [init content]
  (let [capabilities (get-in init [:agentCapabilities :promptCapabilities])]
    (doseq [block content
            :let [type (some-> (or (:type block) (get block "type")) name)]]
      (case type
        ("text" "resource_link") nil
        "image" (when-not (:image capabilities)
                  (throw (ex-info "ACP agent does not accept image prompt content"
                                  {:content block :promptCapabilities capabilities})))
        "audio" (when-not (:audio capabilities)
                  (throw (ex-info "ACP agent does not accept audio prompt content"
                                  {:content block :promptCapabilities capabilities})))
        "resource" (when-not (:embeddedContext capabilities)
                     (throw (ex-info "ACP agent does not accept embedded prompt context"
                                     {:content block :promptCapabilities capabilities})))
        (throw (ex-info "unknown ACP prompt content type"
                        {:content block :type type}))))))

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

(defn- close-process! [{:keys [process reader writer status]}]
  (when status (reset! status {:kind :closed :message "ACP connection is closed"}))
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

(defn- wire-key [key]
  (if (keyword? key)
    (if-let [ns (namespace key)]
      (str ns "/" (name key))
      (name key))
    (str key)))

(defn- mark-dead! [{:keys [status]} cause]
  (when status
    (compare-and-set! status nil
                      {:kind :protocol-error
                       :message (or (ex-message cause) (str cause))})))

(defn- send! [{:keys [writer] :as conn} message]
  (try
    (.write ^java.io.Writer writer (json/write-str message :key-fn wire-key))
    (.write ^java.io.Writer writer "\n")
    (.flush ^java.io.Writer writer)
    (catch Throwable t
      (mark-dead! conn t)
      (throw t))))

(defn- read-line!
  [{:keys [reader process]} timeout-ms on-timeout cancel-grace-ms]
  (let [line-f (future (.readLine ^java.io.BufferedReader reader))
        await  #(if % (deref line-f % ::timeout) @line-f)
        line   (try
                 (let [first-line (await timeout-ms)]
                   (if-not (= ::timeout first-line)
                     first-line
                     (if (and on-timeout (on-timeout))
                       (await (or cancel-grace-ms 1000))
                       ::timeout)))
                 (catch InterruptedException e
                   (future-cancel line-f)
                   (.destroyForcibly ^Process process)
                   (throw e)))]
    (when (= ::timeout line)
      (future-cancel line-f)
      (.destroyForcibly ^Process process)
      (throw (ex-info "ACP agent timed out waiting for a message"
                      {:timeout-ms timeout-ms
                       :cancel-grace-ms cancel-grace-ms})))
    (when-not line
      (throw (ex-info "ACP agent closed stdout before responding" {})))
    line))

(defn- cancel-active-session! [conn state]
  (when-let [session-id (:session-id @state)]
    (when-not (:cancelling? @state)
      (swap! state assoc :cancelling? true :timed-out? true)
      (send! conn {:jsonrpc "2.0"
                   :method "session/cancel"
                   :params {:sessionId session-id}}))
    true))

(defn- read-message! [conn state opts]
  (try
    (json/read-str (read-line! conn
                               (:timeout-ms opts)
                               #(cancel-active-session! conn state)
                               (:cancel-grace-ms opts))
                   :key-fn keyword)
    (catch Throwable t
      (mark-dead! conn t)
      (throw t))))

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
      (if-let [option (or (first (filter #(str/starts-with? (or (:kind %) "") "allow") options))
                          (first options))]
        (selected option)
        {:outcome {:outcome "cancelled"}})

      (= :cancel policy)
      {:outcome {:outcome "cancelled"}}

      :else
      (if-let [option (first (filter #(str/starts-with? (or (:kind %) "") "reject") options))]
        (selected option)
        {:outcome {:outcome "cancelled"}}))))

(defn- handle-fs-request [message]
  (let [{:keys [path line limit content]} (:params message)]
    (case (:method message)
      "fs/read_text_file"
      ;; whole-file reads return the content verbatim (no CRLF normalization
      ;; or synthesized trailing newline); line/limit reads slice 1-based
      ;; lines, and an offset past EOF yields empty content rather than an
      ;; error.
      (let [text (slurp path)]
        (if (and (nil? line) (nil? limit))
          {:result {:content text}}
          (let [lines (vec (str/split-lines text))
                start (min (max 0 (dec (or line 1))) (count lines))
                end   (if limit (min (count lines) (+ start limit)) (count lines))
                slice (subvec lines start end)]
            {:result {:content (str (str/join "\n" slice)
                                    (when (and (seq slice) (< end (count lines)))
                                      "\n"))}})))

      "fs/write_text_file"
      (do
        (io/make-parents path)
        (spit path content)
        {:result nil}))))

(defn- fs-capability
  "The advertised client capability flag for an fs method, nil for non-fs
  methods. `fs.readTextFile` and `fs.writeTextFile` are declared independently,
  so each method is gated on its own flag."
  [opts method]
  (when-let [capability ({"fs/read_text_file"  :readTextFile
                          "fs/write_text_file" :writeTextFile} method)]
    (get-in opts [:client-capabilities :fs capability])))

(defn- handle-request!
  ([conn message opts]
   (handle-request! conn (atom {:terminals (terminal/service)}) message opts))
  ([conn state message opts]
   (let [id (:id message)]
     (try
       (cond
         (= "session/request_permission" (:method message))
         (send! conn (success id (permission-outcome message opts)))

         (fs-capability opts (:method message))
         (let [{:keys [result]} (handle-fs-request message)]
           (send! conn (success id result)))

         (and (true? (get-in opts [:client-capabilities :terminal]))
              (str/starts-with? (:method message) "terminal/"))
         (send! conn (success id (terminal/handle! (:terminals @state)
                                                   (:method message)
                                                   (:params message))))

         (:request-handler opts)
         (let [reply ((:request-handler opts) message)]
           (send! conn (if (and (map? reply) (contains? reply :error))
                         (assoc reply :jsonrpc "2.0" :id id)
                         (success id reply))))

         :else
         (send! conn (error id -32601 "Method not found")))
       (catch InterruptedException e
         (.interrupt (Thread/currentThread))
         (throw e))
       (catch Throwable t
         (send! conn (error id -32603 (or (ex-message t) (str t)))))))))

(defn- observe-message! [message opts]
  (when-let [on-event (:on-event opts)]
    (try (on-event message) (catch Throwable _ nil))))

(defn- remember-update! [state message]
  (when (= "session/update" (:method message))
    (let [session-update (get-in message [:params :update])]
      (swap! state update :updates conj session-update)
      (when (= "agent_message_chunk" (:sessionUpdate session-update))
        (when-let [text (get-in session-update [:content :text])]
          (swap! state update :text conj text))))))

(defn- session-scoped-message? [message]
  (let [method (:method message)]
    (or (= "session/update" method)
        (= "session/request_permission" method)
        (str/starts-with? (or method "") "fs/")
        (str/starts-with? (or method "") "terminal/"))))

(defn- validate-session-id! [state message]
  (when-let [expected (:session-id @state)]
    (when (and (session-scoped-message? message)
               (not= expected (get-in message [:params :sessionId])))
      (throw (ex-info "ACP message has an unexpected sessionId"
                      {:expected expected
                       :actual (get-in message [:params :sessionId])
                       :method (:method message)})))))

(defn- wait-response! [conn id state opts]
  (if-let [message (get-in @state [:responses id])]
    (do
      (swap! state update :responses dissoc id)
      message)
    (loop []
      (let [message (read-message! conn state opts)]
        (validate-session-id! state message)
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
            (handle-request! conn state message
                             (cond-> opts
                               (:cancelling? @state)
                               (assoc :permission-policy :cancel)))
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

(defn- selected-auth-method [init opts]
  (let [methods (:authMethods init)
        selected (or (:auth-method opts)
                     (when-let [selector (:auth-method-selector opts)]
                       (selector methods)))]
    (when selected
      (let [method-id (name selected)]
        (when-not (some #(= method-id (:id %)) methods)
          (throw (ex-info "ACP authentication method was not advertised"
                          {:method-id method-id
                           :available (mapv :id methods)})))
        method-id))))

(defn- authenticate! [conn ids state init opts]
  (when-let [method-id (selected-auth-method init opts)]
    (request! conn ids state "authenticate" {:methodId method-id} opts)
    method-id))

(defn- session-capability? [init capability]
  ;; capabilities are declared as values, so an explicit false means
  ;; unsupported even though the key is present
  (boolean (get-in init [:agentCapabilities :sessionCapabilities capability])))

(defn- check-mcp-servers!
  "HTTP and SSE MCP transports MUST only be sent to agents that advertised the
  matching `mcpCapabilities` flag in `initialize`; stdio needs no capability."
  [init servers]
  (doseq [server servers
          :let [transport (or (:type server) (get server "type"))]]
    (when (and (#{"http" "sse"} transport)
               (not (get-in init [:agentCapabilities :mcpCapabilities
                                  (keyword transport)])))
      (throw (ex-info "ACP agent does not advertise the MCP transport capability"
                      {:transport transport
                       :server server
                       :mcp-capabilities (get-in init [:agentCapabilities
                                                       :mcpCapabilities])})))))

(defn- session-params [init agent opts]
  (let [servers (vec (or (:mcp-servers opts)
                         (get-in agent [:config :mcp-servers])
                         []))]
    (check-mcp-servers! init servers)
    (cond-> {:cwd        (absolute-path (or (:cwd opts) (:dir opts) "."))
             :mcpServers servers}
      (and (:additional-directories opts)
           (session-capability? init :additionalDirectories))
      (assoc :additionalDirectories
             (mapv absolute-path (:additional-directories opts))))))

(defn- session! [conn ids state init agent opts]
  (let [resume (:resume opts)
        params (session-params init agent opts)]
    (cond
      resume
      (cond
        ;; keep the response: load/resume MAY return configOptions and other
        ;; session state that set-config! matches against
        (session-capability? init :resume)
        (-> (request! conn ids state "session/resume"
                      (assoc params :sessionId resume)
                      opts)
            (assoc :sessionId resume))

        (true? (get-in init [:agentCapabilities :loadSession]))
        (-> (request! conn ids state "session/load"
                      (assoc params :sessionId resume)
                      opts)
            (assoc :sessionId resume))

        :else
        (throw (ex-info "ACP agent does not support session resume/load"
                        {:session-id resume
                         :agent-capabilities (:agentCapabilities init)})))

      :else
      (request! conn ids state "session/new" params opts))))

(defn stop-reason-ok?
  "True when an ACP `stopReason` means the prompt turn completed normally.
  Missing, refusal, cancellation, and limit stops are failures."
  [stop-reason]
  (= "end_turn" stop-reason))

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

(defn- validate-config-value! [option value]
  (let [allowed (keep :value (:options option))]
    (when (and (seq allowed) (not (contains? (set (map str allowed)) (str value))))
      (throw (ex-info "ACP config value is not one of the advertised options"
                      {:config-id (:id option)
                       :value value
                       :allowed (vec allowed)})))))

(defn- set-config! [conn ids state session-id session-result agent opts]
  (loop [options (config-options session-result)
         settings (seq (desired-config agent opts))]
    (when-let [[id value] (first settings)]
      (if-let [option (config-option options id)]
        (do
          (validate-config-value! option value)
          (let [response (request! conn ids state "session/set_config_option"
                                   {:sessionId session-id
                                    :configId  (:id option)
                                    :value     (str value)}
                                   opts)]
            (recur (if (contains? response :configOptions)
                     (config-options response)
                     options)
                   (next settings))))
        (if (:strict-config? opts)
          (throw (ex-info "ACP agent did not advertise requested config option"
                          {:config-id id
                           :available (mapv :id options)}))
          (recur options (next settings)))))))

(defn connect!
  "Start an ACP agent process and initialize the protocol once. Returns a
  connection that `acp-runner` reuses across runs when passed as `:connection`,
  so each run pays one `session/new` and one prompt turn instead of a process
  spawn plus `initialize` - the lifecycle ACP is designed for. Close it with
  `close!`.

    (def conn (acp/connect! {:command [\"some-acp-agent\"]}))
    (def runner (acp/acp-runner {:connection conn}))
    ;; ... many runs ...
    (acp/close! conn)

  Prompt turns on one connection are serialized; for parallel branches, give
  each worker its own connection."
  [opts]
  (let [status (atom nil)
        auth-state (atom nil)
        conn  (assoc (start-process opts) :status status)
        ids   (atom 0)
        state (atom {:responses {}})]
    (try
      (let [init (initialize! conn ids state opts)]
        (reset! auth-state (authenticate! conn ids state init opts))
        {:karcarthy/type :acp-connection
         :conn conn
         :ids  ids
         :init init
         :status status
         :auth-state auth-state
         :lock (Object.)})
      (catch Throwable t
        (close-process! conn)
        (throw t)))))

(defn close!
  "Shut down an ACP connection created by `connect!`."
  [{:keys [conn]}]
  (close-process! conn))

(defn- run-on-connection!
  [{:keys [conn ids init lock status auth-state]} agent input opts]
  (when-let [failure (and status @status)]
    (throw (ex-info "ACP connection is not usable"
                    {:connection failure})))
  (locking lock
    (let [state          (atom {:responses {} :updates [] :text []
                                :terminals (terminal/service)})
          requested-auth (selected-auth-method init opts)
          _               (when requested-auth
                            (if-let [authenticated (and auth-state @auth-state)]
                              (when-not (= authenticated requested-auth)
                                (throw (ex-info "ACP connection is already authenticated with another method"
                                                {:authenticated authenticated
                                                 :requested requested-auth})))
                              (when auth-state
                                (reset! auth-state
                                        (authenticate! conn ids state init opts)))))
          session-result (session! conn ids state init agent opts)
          session-id     (:sessionId session-result)]
      (swap! state assoc :session-id session-id)
      (try
        (set-config! conn ids state session-id session-result agent opts)
        ;; session/load replays the prior conversation as agent_message_chunk
        ;; updates before responding, so only chunks streamed during the prompt
        ;; turn belong in :text; replayed history stays in :raw :updates.
        (swap! state assoc :text [])
        (check-prompt-content! init (:prompt-content opts))
        (let [prompt-result (request! conn ids state "session/prompt"
                                      (prompt-params session-id agent input opts)
                                      opts)
              stop-reason   (:stopReason prompt-result)
              timed-out?    (:timed-out? @state)
              text          (str/join "" (:text @state))]
          (k/result
           (cond-> {:agent      (:name agent)
                    :ok?        (and (not timed-out?) (stop-reason-ok? stop-reason))
                    :text       text
                    :session-id session-id
                    :stop-reason stop-reason
                    :raw        {:runner :acp
                                 :session-id session-id
                                 :stop-reason stop-reason
                                 :agent-info (:agentInfo init)
                                 :updates (:updates @state)
                                 :stderr (stderr conn)}}
             timed-out?
             (assoc :error "ACP prompt turn timed out and was cancelled")

             (and (not timed-out?) (not (stop-reason-ok? stop-reason)))
             (assoc :error (str "ACP prompt turn stopped: " stop-reason)))))
        (finally
          (try
            (when (and session-id
                       (session-capability? init :close)
                       (or (nil? status) (nil? @status)))
              (request! conn ids state "session/close" {:sessionId session-id} opts))
            (finally
              (terminal/close! (:terminals @state)))))))))

(defn- run-acp! [agent input opts]
  (if-let [connection (:connection opts)]
    (run-on-connection! connection agent input opts)
    (let [connection (connect! opts)]
      (try
        (run-on-connection! connection agent input opts)
        (finally
          (close! connection))))))

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
    :connection               a connection from `connect!` to reuse across
                              runs (one process and one `initialize` for many
                              prompt turns); without it, each run spawns and
                              tears down its own process
    :cwd / :dir               working directory for the ACP session
    :mcp-servers              ACP MCP server specs
    :client-capabilities      advertised ACP client capabilities
    :auth-method              advertised authentication method id to use
    :auth-method-selector     fn of advertised auth methods -> method id
    :prompt-content           additional ACP content blocks appended to the text prompt
    :config-options           map of ACP session config option ids/categories to values
    :strict-config?           throw when a requested config option is unavailable
    :permission-policy        :reject (default), :allow, or :cancel
    :permission-handler       fn of the permission request -> option id or ACP outcome map
    :request-handler          fn for custom ACP agent->client requests
    :on-event                 fn called with every ACP JSON-RPC message received
    :timeout-ms               max wait per ACP message before `session/cancel`
    :cancel-grace-ms          wait after cancellation before force-killing"
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

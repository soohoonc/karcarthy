(ns karcarthy.mcp
  "Minimal MCP 2025-11-25 stdio client. It discovers server Tools and adapts
  them into ordinary karcarthy Tools; the model loop stays unchanged."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [karcarthy.contract :as contract]
            [karcarthy.tool :as tool])
  (:import [java.io BufferedReader BufferedWriter File InputStreamReader OutputStreamWriter]
           [java.nio.charset StandardCharsets]
           [java.security MessageDigest]
           [java.util.concurrent TimeUnit]))

(def protocol-version "2025-11-25")

(defn connection? [value]
  (and (map? value)
       (= :mcp-connection (:karcarthy/type value))))

(defn- send-message! [connection message]
  (let [^BufferedWriter writer (:writer connection)]
    (locking writer
      (.write writer (json/write-str message))
      (.newLine writer)
      (.flush writer))))

(defn- request! [connection method params]
  (let [id (swap! (:next-id connection) inc)
        response (promise)
        timeout-ms (:timeout-ms connection)]
    (swap! (:pending connection) assoc id response)
    (send-message! connection
                   {:jsonrpc "2.0" :id id :method method :params (or params {})})
    (let [message (deref response timeout-ms ::timeout)]
      (swap! (:pending connection) dissoc id)
      (cond
        (= ::timeout message)
        (contract/fail! :mcp :timeout "MCP request timed out"
                    {:server (:name connection)
                     :method method
                     :timeout-ms timeout-ms})

        (:error message)
        (contract/fail! :mcp :response
                    (or (get-in message [:error :message])
                        "MCP request failed")
                    {:server (:name connection)
                     :method method
                     :error (:error message)})

        :else (:result message)))))

(defn- notification! [connection method params]
  (send-message! connection
                 {:jsonrpc "2.0" :method method :params (or params {})}))

(defn- fail-pending! [connection message]
  (doseq [[_ response] @(:pending connection)]
    (deliver response {:error {:code -32000 :message message}})))

(defn- handle-server-request! [connection message]
  (send-message!
   connection
   (if (= "ping" (:method message))
     {:jsonrpc "2.0" :id (:id message) :result {}}
     {:jsonrpc "2.0"
      :id (:id message)
      :error {:code -32601
              :message (str "Unsupported MCP server request: "
                            (:method message))}})))

(defn- reader-loop! [connection]
  (let [^BufferedReader reader (:reader connection)]
    (try
      (loop []
        (when-let [line (.readLine reader)]
          (when-not (str/blank? line)
            (let [message (json/read-str line :key-fn keyword)]
              (cond
                (get @(:pending connection) (:id message))
                (deliver (get @(:pending connection) (:id message)) message)

                (and (some? (:id message)) (:method message))
                (handle-server-request! connection message)

                (= "notifications/tools/list_changed" (:method message))
                (reset! (:tools-stale? connection) true))))
          (recur)))
      (catch Throwable error
        (fail-pending! connection
                       (or (ex-message error) "MCP connection closed")))
      (finally
        (fail-pending! connection "MCP connection closed")))))

(defn- stderr-loop! [connection]
  (let [^BufferedReader reader (:stderr connection)]
    (try
      (loop []
        (when-let [line (.readLine reader)]
          (swap! (:stderr-lines connection)
                 (fn [lines] (->> (conj lines line) (take-last 100) vec)))
          (recur)))
      (catch Throwable _ nil))))

(defn- environment! [^ProcessBuilder builder env]
  (let [target (.environment builder)
        pairs (cond
                (map? env) env
                (sequential? env)
                (into {}
                      (keep (fn [entry]
                              (when (and (map? entry)
                                         (or (:name entry) (get entry "name")))
                                [(or (:name entry) (get entry "name"))
                                 (or (:value entry) (get entry "value") "")])))
                      env)
                :else {})]
    (doseq [[key value] pairs]
      (.put target (str key) (str value))))
  builder)

(defn- list-tools! [connection]
  (loop [cursor nil
         result []]
    (let [page (request! connection "tools/list"
                         (cond-> {} cursor (assoc :cursor cursor)))
          accumulated (into result (:tools page))]
      (if-let [next-cursor (:nextCursor page)]
        (recur next-cursor accumulated)
        (do
          (reset! (:tool-definitions connection) accumulated)
          (reset! (:tools-stale? connection) false)
          accumulated)))))

(defn connect!
  "Start and initialize a trusted stdio MCP server.

  Config: `{:name string :command string :args [string] :env map-or-vector
  :cwd string}`. The command is executed directly, never through a shell."
  [{:keys [name command args env timeout-ms cwd] :as config}]
  (when-not (and (string? command) (not (str/blank? command)))
    (contract/fail! :contract :configuration
                "MCP stdio config requires :command"
                {:config (dissoc config :env)}))
  (let [directory (when cwd (File. (str cwd)))
        _ (when (and directory (not (.isDirectory directory)))
            (contract/fail! :contract :configuration
                        "MCP :cwd must be an existing directory"
                        {:cwd (str cwd)}))
        name (or name command)
        builder (environment! (ProcessBuilder. ^java.util.List
                                               (vec (cons command (or args []))))
                              env)
        _ (when directory (.directory builder directory))
        process (.start builder)
        connection {:karcarthy/type :mcp-connection
                    :name name
                    :config (dissoc config :env)
                    :process process
                    :reader (BufferedReader.
                             (InputStreamReader. (.getInputStream process)
                                                 StandardCharsets/UTF_8))
                    :stderr (BufferedReader.
                             (InputStreamReader. (.getErrorStream process)
                                                 StandardCharsets/UTF_8))
                    :writer (BufferedWriter.
                             (OutputStreamWriter. (.getOutputStream process)
                                                  StandardCharsets/UTF_8))
                    :pending (atom {})
                    :next-id (atom 0)
                    :tool-definitions (atom [])
                    :tools-stale? (atom true)
                    :stderr-lines (atom [])
                    :timeout-ms (long (or timeout-ms 30000))}
        reader-thread (Thread/startVirtualThread
                       ^Runnable #(reader-loop! connection))
        stderr-thread (Thread/startVirtualThread
                       ^Runnable #(stderr-loop! connection))
        connection (assoc connection
                          :reader-thread reader-thread
                          :stderr-thread stderr-thread)]
    (try
      (let [initialized
            (request! connection "initialize"
                      {:protocolVersion protocol-version
                       :capabilities {}
                       :clientInfo {:name "karcarthy"
                                    :title "karcarthy"
                                    :version "0.0.2"}})]
        (when-not (= protocol-version (:protocolVersion initialized))
          (contract/fail! :mcp :initialize
                      "MCP server selected an unsupported protocol version"
                      {:requested protocol-version
                       :selected (:protocolVersion initialized)}))
        (notification! connection "notifications/initialized" {})
        (list-tools! connection)
        (assoc connection
               :server-info (:serverInfo initialized)
               :capabilities (:capabilities initialized)))
      (catch Throwable error
        (.destroyForcibly process)
        (throw error)))))

(defn close! [connection]
  (when (connection? connection)
    (try (.close ^BufferedWriter (:writer connection)) (catch Throwable _ nil))
    (let [process ^Process (:process connection)]
      (when (.isAlive process)
        (.destroy process)
        (when-not (.waitFor process 2 TimeUnit/SECONDS)
          (.destroyForcibly process))))
    true))

(defn definitions [connection]
  (when @(:tools-stale? connection) (list-tools! connection))
  @(:tool-definitions connection))

(defn call!
  "Call one MCP Tool and return its protocol result without hiding execution
  errors from the model."
  [connection name arguments]
  (request! connection "tools/call"
            {:name name :arguments (or arguments {})}))

(defn- safe-name [value]
  (let [name (-> (str value)
                 (str/replace #"[^A-Za-z0-9_-]+" "_")
                 (str/replace #"^_+|_+$" ""))]
    (if (str/blank? name) "tool" name)))

(defn- short-hash [value]
  (let [digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes (str value) StandardCharsets/UTF_8))]
    (apply str (map #(format "%02x" (bit-and (int %) 0xff))
                    (take 5 digest)))))

(defn- bounded-name [prefix remote-name collision?]
  (let [base (str prefix (safe-name remote-name))
        suffix (when (or collision? (> (count base) 64))
                 (str "_" (short-hash remote-name)))
        available (- 64 (count (or suffix "")))]
    (str (subs base 0 (min available (count base))) suffix)))

(defn tools
  "Adapt every discovered MCP Tool into an ordinary karcarthy Tool.

  Unknown MCP servers require approval by default. Set `:approval :never` only
  for a server whose command and tool implementations you trust."
  ([connection] (tools connection {}))
  ([connection {:keys [prefix approval]
                :or {approval :always}}]
   (let [definitions (definitions connection)
         prefix (-> (or prefix
                        (str "mcp_" (safe-name (:name connection)) "__"))
                    str
                    (str/replace #"[^A-Za-z0-9_-]+" "_"))
         bases (mapv #(str prefix (safe-name (:name %))) definitions)
         frequencies (frequencies bases)]
     (mapv
      (fn [definition]
        (let [remote-name (:name definition)
              base (str prefix (safe-name remote-name))
              local-name (bounded-name prefix remote-name
                                       (> (get frequencies base 0) 1))
              input-schema (or (:inputSchema definition)
                               {:type "object" :additionalProperties false})]
          (tool/make-tool
           {:name local-name
            :description
            (str "MCP " (:name connection) "/" remote-name ": "
                 (or (:description definition) "No description provided."))
            :input input-schema
            :input-schema input-schema
            :output any?
            :approval approval
            :metadata {:mcp/server (:name connection)
                       :mcp/tool remote-name
                       :mcp/annotations (:annotations definition)}}
           `(karcarthy.mcp/tool ~(:name connection) ~remote-name)
           nil
           (fn [_ arguments]
             (let [result (call! connection remote-name arguments)]
               {:is_error (boolean (:isError result))
                :content (:content result)
                :structured_content (:structuredContent result)})))))
      definitions))))

(ns karcarthy.terminal
  "Internal lifecycle for ACP client terminals.

  Terminals are opt-in through `clientCapabilities.terminal`. Each run owns a
  registry, commands stream combined stdout/stderr into a UTF-8-safe bounded
  buffer, and unreleased processes are cleaned up when the run ends."
  (:require [clojure.java.io :as io])
  (:import [java.nio.charset StandardCharsets]
           [java.util UUID]
           [java.util.concurrent TimeUnit]))

(def ^:private default-output-byte-limit (* 1024 1024))

(defn service
  "Create an empty terminal registry for one ACP prompt turn."
  []
  (atom {}))

(defn- param [m key]
  (or (get m key) (get m (name key))))

(defn- utf8-size [s]
  (alength (.getBytes ^String s StandardCharsets/UTF_8)))

(defn- suffix-within-limit [s limit]
  (if (<= (utf8-size s) limit)
    s
    (let [length (count s)
          start  (loop [low 0, high length]
                   (if (< low high)
                     (let [mid (quot (+ low high) 2)]
                       (if (> (utf8-size (subs s mid)) limit)
                         (recur (inc mid) high)
                         (recur low mid)))
                     low))
          start  (if (and (< start length)
                          (Character/isLowSurrogate (.charAt ^String s start)))
                   (inc start)
                   start)]
      (subs s start))))

(defn- append-output! [{:keys [output truncated? output-byte-limit]} chunk]
  (swap! output
         (fn [current]
           (let [combined (str current chunk)
                 retained (suffix-within-limit combined output-byte-limit)]
             (when (not= combined retained) (reset! truncated? true))
             retained))))

(defn- start-reader! [terminal]
  (future
    (try
      (with-open [reader (io/reader (.getInputStream ^Process (:process terminal))
                                    :encoding "UTF-8")]
        (let [buffer (char-array 4096)]
          (loop []
            (let [n (.read ^java.io.Reader reader buffer)]
              (when (pos? n)
                (append-output! terminal (String. buffer 0 n))
                (recur))))))
      (catch Throwable _ nil))))

(defn- command-vector [params]
  (let [command (param params :command)
        args    (or (param params :args) [])]
    (when-not (and (string? command) (seq command)
                   (sequential? args) (every? string? args))
      (throw (ex-info "terminal/create requires a command string and string args"
                      {:command command :args args})))
    (into [command] args)))

(defn- output-limit [params]
  (let [limit (or (param params :outputByteLimit) default-output-byte-limit)]
    (when-not (and (integer? limit) (not (neg? limit)))
      (throw (ex-info "terminal outputByteLimit must be a non-negative integer"
                      {:outputByteLimit limit})))
    (long limit)))

(defn- create! [registry params]
  (let [cwd (param params :cwd)]
    (when-not (and (string? cwd) (.isAbsolute (io/file cwd)))
      (throw (ex-info "terminal/create requires an absolute cwd" {:cwd cwd})))
    (let [pb (doto (ProcessBuilder. ^java.util.List (command-vector params))
               (.directory (io/file cwd))
               (.redirectErrorStream true))]
      (doseq [entry (or (param params :env) [])]
        (let [name  (param entry :name)
              value (param entry :value)]
          (when-not (and (string? name) (string? value))
            (throw (ex-info "terminal env entries require string name/value"
                            {:entry entry})))
          (.put (.environment pb) name value)))
      (let [id       (str (UUID/randomUUID))
            terminal {:process (.start pb)
                      :output (atom "")
                      :truncated? (atom false)
                      :output-byte-limit (output-limit params)}
            terminal (assoc terminal :reader (start-reader! terminal))]
        (swap! registry assoc id terminal)
        {:terminalId id}))))

(defn- terminal! [registry params]
  (let [id (param params :terminalId)]
    (or (get @registry id)
        (throw (ex-info "unknown ACP terminal" {:terminalId id})))))

(defn- exit-status [terminal]
  (let [process ^Process (:process terminal)]
    (when-not (.isAlive process)
      {:exitCode (.exitValue process) :signal nil})))

(defn- await-reader! [terminal]
  (when-let [reader (:reader terminal)]
    (deref reader 1000 nil)))

(defn- output! [registry params]
  (let [terminal (terminal! registry params)]
    (when-not (.isAlive ^Process (:process terminal))
      (await-reader! terminal))
    (cond-> {:output @(:output terminal)
             :truncated @(:truncated? terminal)}
      (exit-status terminal) (assoc :exitStatus (exit-status terminal)))))

(defn- wait-for-exit! [registry params]
  (let [terminal (terminal! registry params)
        process  ^Process (:process terminal)]
    (.waitFor process)
    (await-reader! terminal)
    (exit-status terminal)))

(defn- kill-process! [terminal]
  (let [process ^Process (:process terminal)]
    (when (.isAlive process)
      (.destroy process)
      (when-not (.waitFor process 200 TimeUnit/MILLISECONDS)
        (.destroyForcibly process)
        (.waitFor process 1000 TimeUnit/MILLISECONDS)))
    (await-reader! terminal)))

(defn- kill! [registry params]
  (kill-process! (terminal! registry params))
  {})

(defn- release! [registry params]
  (let [id (param params :terminalId)
        terminal (terminal! registry params)]
    (kill-process! terminal)
    (swap! registry dissoc id)
    {}))

(defn handle!
  "Handle one capability-gated ACP `terminal/*` request."
  [registry method params]
  (case method
    "terminal/create"        (create! registry params)
    "terminal/output"        (output! registry params)
    "terminal/wait_for_exit" (wait-for-exit! registry params)
    "terminal/kill"          (kill! registry params)
    "terminal/release"       (release! registry params)
    (throw (ex-info "unknown ACP terminal method" {:method method}))))

(defn close!
  "Kill and release every terminal still owned by a prompt turn."
  [registry]
  (doseq [terminal (vals @registry)]
    (try (kill-process! terminal) (catch Throwable _ nil)))
  (reset! registry {})
  nil)

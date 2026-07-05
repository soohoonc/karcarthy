(ns karcarthy.proc
  "Subprocess execution with timeout - the shared primitive behind the
  subprocess-backed runners (`claude-runner`, `codex-runner`, `process-runner`,
  `openai-runner`).

  Uses `ProcessBuilder` directly (so the process can be killed on timeout) and
  drains stdout/stderr on separate threads to avoid pipe-buffer deadlock."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [karcarthy.core :as k])
  (:import [java.lang ProcessHandle]
           [java.util.concurrent TimeUnit]))

(def ^:private cleanup-timeout-ms 1000)

(defn write-stdin!
  "Write `in` to `proc`'s stdin as UTF-8, then close it. Does nothing when
  `in` is nil. The process may exit before reading stdin; the resulting
  broken pipe is ignored."
  [^Process proc in]
  (when (some? in)
    (try
      (with-open [os (.getOutputStream proc)]
        (.write os (.getBytes (str in) "UTF-8"))
        (.flush os))
      (catch java.io.IOException _ nil))))

(defn- close-streams! [^Process proc]
  (doseq [stream [(.getOutputStream proc)
                  (.getInputStream proc)
                  (.getErrorStream proc)]]
    (try (.close stream) (catch Throwable _ nil))))

(defn terminate!
  "Force-kill `proc` and its current descendants, then close its streams."
  [^Process proc]
  (try
    (with-open [descendants (.descendants (.toHandle proc))]
      (doseq [^ProcessHandle handle
              (reverse (vec (iterator-seq (.iterator descendants))))]
        (when (.isAlive handle) (.destroyForcibly handle))))
    (catch Throwable _ nil))
  (when (.isAlive proc) (.destroyForcibly proc))
  (try (.waitFor proc cleanup-timeout-ms TimeUnit/MILLISECONDS)
       (catch InterruptedException _ nil))
  (close-streams! proc))

(defn- future-text [f]
  (try
    (let [value (deref f cleanup-timeout-ms ::timeout)]
      (if (= ::timeout value)
        (do (future-cancel f) "")
        value))
    (catch Throwable _ "")))

(defn- remaining-ms [started-ns timeout-ms]
  (when timeout-ms
    (max 0 (- (long timeout-ms)
              (long (/ (- (System/nanoTime) started-ns) 1000000))))))

(defn run
  "Run `argv` (a vector of strings) as a subprocess. `opts`:
    :in          string written to the process's stdin (then closed)
    :dir         working directory
    :env         map of extra env vars, merged over the inherited environment
    :timeout-ms  kill the process if it runs longer than this (milliseconds)

  Returns {:exit <int|nil> :out <string> :err <string> :timed-out? <bool>}. On
  timeout the process is force-killed, :timed-out? is true and :exit is nil."
  [argv {:keys [in dir env timeout-ms]}]
  (let [started-ns (System/nanoTime)
        pb (ProcessBuilder. ^java.util.List (vec argv))]
    (when dir (.directory pb (io/file (str dir))))
    (when (seq env)
      (let [e (.environment pb)]
        (doseq [[k v] env] (.put e (str k) (str v)))))
    (let [proc  (.start pb)
          out-f (future (slurp (.getInputStream proc)))
          err-f (future (slurp (.getErrorStream proc)))
          in-f  (future (write-stdin! proc in))]
      (try
        (let [remaining (remaining-ms started-ns timeout-ms)
              finished? (if timeout-ms
                          (and (pos? remaining)
                               (.waitFor proc remaining TimeUnit/MILLISECONDS))
                          (do (.waitFor proc) true))]
          (if finished?
            {:exit (.exitValue proc)
             :timed-out? false
             :out (future-text out-f)
             :err (future-text err-f)}
            (do
              (terminate! proc)
              {:exit nil
               :timed-out? true
               :out (future-text out-f)
               :err (future-text err-f)})))
        (catch InterruptedException e
          (terminate! proc)
          (.interrupt (Thread/currentThread))
          (throw e))
        (finally
          (future-cancel in-f)
          (when (.isAlive proc) (terminate! proc)))))))

(defn ->result
  "Build a karcarthy result from a `run` outcome. `label` names the tool in
  error text; `raw` is merged over the outcome for the :raw payload."
  [{:keys [exit out timed-out?] :as outcome} {:keys [agent label trim? raw]}]
  (k/result {:agent agent
             :ok?   (and (not timed-out?) (= 0 exit))
             :text  (cond-> (or out "") trim? str/trim)
             :error (cond timed-out?    (str label " timed out")
                          (not= 0 exit) (str label " exited with status " exit))
             :raw   (merge outcome raw)}))

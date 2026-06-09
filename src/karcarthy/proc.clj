(ns karcarthy.proc
  "Subprocess execution with timeout - the shared primitive behind the
  subprocess-backed runners (`claude-cli-runner`, `process-runner`,
  `openai-agents-runner`).

  Uses `ProcessBuilder` directly (so the process can be killed on timeout) and
  drains stdout/stderr on separate threads to avoid pipe-buffer deadlock."
  (:require [clojure.java.io :as io])
  (:import [java.util.concurrent TimeUnit]))

(defn run
  "Run `argv` (a vector of strings) as a subprocess. `opts`:
    :in          string written to the process's stdin (then closed)
    :dir         working directory
    :env         map of extra env vars, merged over the inherited environment
    :timeout-ms  kill the process if it runs longer than this (milliseconds)

  Returns {:exit <int|nil> :out <string> :err <string> :timed-out? <bool>}. On
  timeout the process is force-killed, :timed-out? is true and :exit is nil."
  [argv {:keys [in dir env timeout-ms]}]
  (let [pb (ProcessBuilder. ^java.util.List (vec argv))]
    (when dir (.directory pb (io/file (str dir))))
    (when (seq env)
      (let [e (.environment pb)]
        (doseq [[k v] env] (.put e (str k) (str v)))))
    (let [proc  (.start pb)
          out-f (future (slurp (.getInputStream proc)))
          err-f (future (slurp (.getErrorStream proc)))]
      (when (some? in)
        ;; the process may exit before reading stdin; ignore the broken pipe
        (try
          (with-open [os (.getOutputStream proc)]
            (.write os (.getBytes (str in) "UTF-8"))
            (.flush os))
          (catch java.io.IOException _ nil)))
      (let [finished? (if timeout-ms
                        (.waitFor proc timeout-ms TimeUnit/MILLISECONDS)
                        (do (.waitFor proc) true))]
        (if finished?
          {:exit (.exitValue proc) :timed-out? false :out @out-f :err @err-f}
          (do (.destroyForcibly proc)
              {:exit       nil
               :timed-out? true
               :out        (try @out-f (catch Throwable _ ""))
               :err        (try @err-f (catch Throwable _ ""))}))))))

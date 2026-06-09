(ns karcarthy.runner.process
  "Runners for local process execution.

  `process-runner` executes an argv vector directly, or executes a shell command
  string through `sh -lc`. It writes the input to stdin and turns stdout into the
  result text."
  (:require [clojure.string :as str]
            [karcarthy.core :as k]
            [karcarthy.proc :as proc]))

(defn- run-process
  [mode argv agent-name input {:keys [trim? env dir timeout-ms]}]
  (let [{:keys [exit out err timed-out?]}
        (proc/run argv {:in input :env env :dir dir :timeout-ms timeout-ms})]
    (k/result {:agent agent-name
               :ok?   (and (not timed-out?) (= 0 exit))
               :text  (cond-> (or out "") trim? str/trim)
               :error (cond timed-out?    "process timed out"
                            (not= 0 exit) (str "process exited with status " exit))
               :raw   {:runner :process
                       :mode mode
                       :exit exit
                       :out out
                       :err err
                       :timed-out? timed-out?
                       :argv (vec argv)}})))

(defn- command->argv
  [command shell]
  (cond
    (string? command) (conj (vec shell) command)
    (sequential? command) (vec command)
    :else (throw (ex-info "process-runner command must be an argv vector or shell command string"
                          {:command command
                           :supported [:argv-vector :shell-string]}))))

(defn process-runner
  "Build a process runner. `command` is either:

    [\"cmd\" \"arg\"]  an argv vector executed directly
    \"cmd arg\"       a shell command string executed with `:shell`

  The command is fixed runner configuration. Select different process commands
  with the runner registry and agent `:runner` keys, not with agent-aware command
  builder functions. Options:

    :trim?       trim surrounding whitespace from stdout (default true)
    :env         extra environment variables (merged over the current env)
    :dir         working directory for the process
    :timeout-ms  kill the command if it runs longer than this (milliseconds)
    :shell       argv prefix used for string commands (default [\"sh\" \"-lc\"])

    (process-runner [\"cat\"])           ; echoes stdin
    (process-runner [\"tr\" \"a-z\" \"A-Z\"]) ; uppercases stdin
    (process-runner \"tr a-z A-Z\")      ; shell form"
  ([command] (process-runner command {}))
  ([command {:keys [trim? env dir timeout-ms shell]
             :or   {trim? true shell ["sh" "-lc"]}}]
   (let [mode (if (string? command) :shell :exec)
         argv (command->argv command shell)]
     (reify k/Runner
       (-run [_ agent input _opts]
         (run-process mode argv (:name agent) input
                      {:trim? trim?
                       :env env
                       :dir dir
                       :timeout-ms timeout-ms}))))))

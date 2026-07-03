(ns karcarthy.runner.process
  "Runners for local process execution.

  `process-runner` executes an argv vector directly, or executes a shell command
  string through `sh -c`. It writes the input to stdin and turns stdout into the
  result text."
  (:require [karcarthy.core :as k]
            [karcarthy.proc :as proc]))

(defn- run-process
  [mode argv agent-name input {:keys [trim? env dir timeout-ms]}]
  (proc/->result (proc/run argv {:in input :env env :dir dir :timeout-ms timeout-ms})
                 {:agent agent-name :label "process" :trim? trim?
                  :raw   {:runner :process :mode mode :argv (vec argv)}}))

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

  The command is fixed runner configuration. If different parts of an
  application need different commands, choose the process runner before calling
  `run`. Options:

    :trim?       trim surrounding whitespace from stdout (default true)
    :env         extra environment variables (merged over the current env)
    :dir         working directory for the process
    :timeout-ms  kill the command if it runs longer than this (milliseconds)
    :shell       argv prefix used for string commands (default [\"sh\" \"-c\"]).
                 Deliberately not a login shell: profile scripts that write to
                 stdout would corrupt the result text. Pass [\"bash\" \"-lc\"]
                 if a command needs the login environment.

    (process-runner [\"cat\"])           ; echoes stdin
    (process-runner [\"tr\" \"a-z\" \"A-Z\"]) ; uppercases stdin
    (process-runner \"tr a-z A-Z\")      ; shell form"
  ([command] (process-runner command {}))
  ([command {:keys [trim? env dir timeout-ms shell]
             :or   {trim? true shell ["sh" "-c"]}}]
   (let [mode (if (string? command) :shell :exec)
         argv (command->argv command shell)]
     (reify k/Runner
       (-run [_ agent input _opts]
         (run-process mode argv (:name agent) input
                      {:trim? trim?
                       :env env
                       :dir dir
                       :timeout-ms timeout-ms}))))))

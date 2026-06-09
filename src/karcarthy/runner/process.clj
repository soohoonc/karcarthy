(ns karcarthy.runner.process
  "Runners for local process execution.

  `process-runner` executes an argv vector directly. `shell-runner` executes a
  shell command string through `sh -lc`. Both write the prompt to stdin and turn
  stdout into the result text."
  (:require [clojure.string :as str]
            [karcarthy.core :as k]
            [karcarthy.proc :as proc]))

(defn- run-process
  [kind argv agent prompt {:keys [trim? env dir timeout-ms]}]
  (let [{:keys [exit out err timed-out?]}
        (proc/run argv {:in prompt :env env :dir dir :timeout-ms timeout-ms})]
    (k/result {:agent (:name agent)
               :ok?   (and (not timed-out?) (= 0 exit))
               :text  (cond-> (or out "") trim? str/trim)
               :error (cond timed-out?    (str (name kind) " timed out")
                            (not= 0 exit) (str (name kind) " exited with status " exit))
               :raw   {:runner kind
                       :exit exit
                       :out out
                       :err err
                       :timed-out? timed-out?
                       :argv (vec argv)}})))

(defn process-runner
  "Build a process runner. `argv-or-fn` is either a fixed argv vector or a fn of
  the agent map returning an argv vector. Options:
    :trim?       trim surrounding whitespace from stdout (default true)
    :env         extra environment variables (merged over the current env)
    :dir         working directory for the process
    :timeout-ms  kill the command if it runs longer than this (milliseconds)

    (process-runner [\"cat\"])                         ; echoes stdin
    (process-runner [\"tr\" \"a-z\" \"A-Z\"])               ; uppercases stdin
    (process-runner (fn [a] [\"ollama\" \"run\" (:model a)]))"
  ([argv-or-fn] (process-runner argv-or-fn {}))
  ([argv-or-fn {:keys [trim? env dir timeout-ms] :or {trim? true}}]
   (let [->argv (if (fn? argv-or-fn) argv-or-fn (constantly argv-or-fn))]
     (reify k/Runner
       (-run [_ agent prompt _opts]
         (run-process :process (->argv agent) agent prompt
                      {:trim? trim?
                       :env env
                       :dir dir
                       :timeout-ms timeout-ms}))))))

(defn shell-runner
  "Build a shell runner. `command-or-fn` is either a fixed shell command string
  or a fn of the agent map returning a command string. Options:
    :trim?       trim surrounding whitespace from stdout (default true)
    :env         extra environment variables (merged over the current env)
    :dir         working directory for the process
    :timeout-ms  kill the command if it runs longer than this (milliseconds)
    :shell       argv prefix used to launch the shell (default [\"sh\" \"-lc\"])

    (shell-runner \"cat\")          ; echoes stdin
    (shell-runner \"tr a-z A-Z\")   ; uppercases stdin"
  ([command-or-fn] (shell-runner command-or-fn {}))
  ([command-or-fn {:keys [trim? env dir timeout-ms shell]
                   :or   {trim? true shell ["sh" "-lc"]}}]
   (let [->command (if (fn? command-or-fn) command-or-fn (constantly command-or-fn))]
     (reify k/Runner
       (-run [_ agent prompt _opts]
         (run-process :shell (conj (vec shell) (->command agent)) agent prompt
                      {:trim? trim?
                       :env env
                       :dir dir
                       :timeout-ms timeout-ms}))))))

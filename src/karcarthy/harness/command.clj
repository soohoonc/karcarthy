(ns karcarthy.harness.command
  "A runner that runs an arbitrary external command as an agent: the agent's
  prompt is written to the process's stdin and its stdout becomes the result
  :text. Use it to wrap any CLI as an agent - a local model (`ollama run ...`),
  Simon Willison's `llm`, a shell script, or `cat`/`tr` in tests.

  The command is chosen per-agent by a function (agent -> argv), so an agent's
  :model (or any field) can select the binary or flags. This keeps karcarthy's
  orchestration provider-neutral: the same workflow runs over Claude, a local
  model, or a deterministic script just by swapping the runner."
  (:require [clojure.string :as str]
            [karcarthy.core :as k]
            [karcarthy.proc :as proc]))

(defn command-runner
  "Build a command runner. `argv-or-fn` is either a fixed argv vector or a fn of
  the agent map returning an argv vector. Options:
    :trim?       trim surrounding whitespace from stdout (default true)
    :env         extra environment variables (merged over the current env)
    :dir         working directory for the process
    :timeout-ms  kill the command if it runs longer than this (milliseconds)

    (command-runner [\"cat\"])                         ; echoes stdin
    (command-runner [\"tr\" \"a-z\" \"A-Z\"])               ; uppercases stdin
    (command-runner (fn [a] [\"ollama\" \"run\" (:model a)]))"
  ([argv-or-fn] (command-runner argv-or-fn {}))
  ([argv-or-fn {:keys [trim? env dir timeout-ms] :or {trim? true}}]
   (let [->argv (if (fn? argv-or-fn) argv-or-fn (constantly argv-or-fn))]
     (reify k/Runner
       (-run [_ agent prompt _opts]
         (let [argv (->argv agent)
               {:keys [exit out err timed-out?]}
               (proc/run argv {:in prompt :env env :dir dir :timeout-ms timeout-ms})]
           (k/result {:agent (:name agent)
                      :ok?   (and (not timed-out?) (= 0 exit))
                      :text  (cond-> (or out "") trim? str/trim)
                      :error (cond timed-out?    "command timed out"
                                   (not= 0 exit) (str "command exited with status " exit))
                      :raw   {:exit exit :out out :err err
                              :timed-out? timed-out? :argv (vec argv)}})))))))

(defn command-harness
  "Deprecated alias for `command-runner`."
  ([argv-or-fn] (command-runner argv-or-fn))
  ([argv-or-fn opts] (command-runner argv-or-fn opts)))

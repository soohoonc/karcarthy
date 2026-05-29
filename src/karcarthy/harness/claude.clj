(ns karcarthy.harness.claude
  "Harness adapter that drives the Claude Agent SDK via the `claude` CLI in
  headless (`-p`) mode. Each agent run is one `claude -p` invocation:

    claude -p <prompt> --output-format json
           --append-system-prompt <instructions>
           [--model M] [--allowedTools t1,t2] [--max-turns N] ...

  The command builder (`claude-command`) is a pure function returning a vector
  of strings — the command is data too, so you can inspect or transform it
  before running. `claude-harness` wraps it as a `karcarthy.core/Harness`."
  (:require [clojure.data.json :as json]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [karcarthy.core :as k]))

(defn claude-command
  "Pure: build the argv (vector of strings) to run `agent` on `prompt`.

  `opts`:
    :claude-bin          path to the CLI (default \"claude\")
    :output-format       \"json\" (default), \"text\", or \"stream-json\"
    :system-prompt-mode  :append (default) -> --append-system-prompt
                         :replace          -> --system-prompt
    :max-turns           integer cap on agent turns
    :permission-mode     e.g. :acceptEdits, :bypassPermissions, :default
    :extra-args          vector of strings appended verbatim (escape hatch for
                         --add-dir, --mcp-config, --session-id, ...)

  Agent fields used: :instructions, :model, :tools (-> --allowedTools)."
  [agent prompt {:keys [claude-bin output-format system-prompt-mode
                        max-turns permission-mode extra-args]
                 :or   {claude-bin "claude"
                        output-format "json"
                        system-prompt-mode :append}}]
  (let [{:keys [instructions model tools]} agent
        sp-flag (if (= system-prompt-mode :replace)
                  "--system-prompt"
                  "--append-system-prompt")]
    (cond-> [claude-bin "-p" prompt "--output-format" output-format]
      instructions     (into [sp-flag instructions])
      model            (into ["--model" model])
      (seq tools)      (into ["--allowedTools" (str/join "," tools)])
      max-turns        (into ["--max-turns" (str max-turns)])
      permission-mode  (into ["--permission-mode" (name permission-mode)])
      (seq extra-args) (into (vec extra-args)))))

(defn parse-result
  "Parse the stdout of `claude -p --output-format json` into a karcarthy result
  map. `agent-name` labels the result. Exposed for unit testing against
  captured JSON."
  [agent-name stdout]
  (let [m (json/read-str stdout :key-fn keyword)]
    (k/result {:agent      agent-name
               :ok?        (not (true? (:is_error m)))
               :text       (:result m)
               :subtype    (:subtype m)        ; e.g. "success", "error_max_turns"
               :num-turns  (:num_turns m)
               :session-id (:session_id m)
               :cost-usd   (:total_cost_usd m)
               :usage      (:usage m)
               :raw        m})))

(defn- run-claude
  [agent prompt opts]
  (let [output-format (get opts :output-format "json")
        argv          (claude-command agent prompt opts)
        ;; :dir runs the agent in a chosen working directory (clojure.java.shell
        ;; takes :dir as a trailing kwarg).
        sh-argv       (if-let [d (:dir opts)] (concat argv [:dir (str d)]) argv)
        {:keys [exit out err]} (apply shell/sh sh-argv)]
    (cond
      ;; JSON mode: claude emits a structured result (with cost, subtype and
      ;; partial text) even when it errors and exits non-zero, so prefer parsing
      ;; stdout and let the payload's `is_error` decide `:ok?`.
      (and (= output-format "json") (seq (str/trim (or out ""))))
      (try
        (parse-result (:name agent) out)
        (catch Exception e
          (k/result {:agent (:name agent) :ok? false
                     :text  (or (not-empty err) out)
                     :error (str "could not parse claude JSON: " (.getMessage e))
                     :raw   {:exit exit :out out :err err :argv argv}})))

      ;; Non-JSON output (or empty stdout): trust the exit code.
      (zero? exit)
      (k/result {:agent (:name agent) :ok? true
                 :text  out
                 :raw   {:out out :err err}})

      :else
      (k/result {:agent (:name agent) :ok? false
                 :text  (or (not-empty err) out)
                 :error (str "claude exited with status " exit)
                 :raw   {:exit exit :out out :err err :argv argv}}))))

(defn claude-harness
  "A `karcarthy.core/Harness` that drives `claude -p`. `default-opts` are merged
  beneath per-run opts (per-run wins). See `claude-command` for command-building
  option keys; additionally `:dir` sets the agent's working directory.

    (require '[karcarthy.core :as k]
             '[karcarthy.harness.claude :as cc])
    (k/run-agent (cc/claude-harness {:max-turns 4})
                 (k/agent \"haiku\" \"Be terse.\" :model \"haiku\")
                 \"Say hi\")"
  ([] (claude-harness {}))
  ([default-opts]
   (reify k/Harness
     (-run [_ agent prompt opts]
       (run-claude agent prompt (merge default-opts opts))))))

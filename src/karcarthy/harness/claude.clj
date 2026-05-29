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
            [clojure.java.io :as io]
            [clojure.string :as str]
            [karcarthy.core :as k]
            [karcarthy.proc :as proc]))

(defn claude-command
  "Pure: build the argv (vector of strings) to run `agent` on `prompt`.

  `opts`:
    :claude-bin          path to the CLI (default \"claude\")
    :output-format       \"json\" (default), \"text\", or \"stream-json\"
    :system-prompt-mode  :append (default) -> --append-system-prompt
                         :replace          -> --system-prompt
    :max-turns           integer cap on agent turns
    :permission-mode     e.g. :acceptEdits, :bypassPermissions, :default
    :resume              session id to resume (-> --resume <id>); enables
                         sessions and handoffs (the agent sees prior context)
    :continue?           resume the most recent session (-> --continue)
    :partial-messages?   stream token-level chunks (-> --include-partial-messages,
                         only meaningful with :output-format \"stream-json\")
    :extra-args          vector of strings appended verbatim (escape hatch for
                         --add-dir, --mcp-config, --disallowedTools, ...)

  Agent fields used: :instructions, :model, :tools (-> --allowedTools)."
  [agent prompt {:keys [claude-bin output-format system-prompt-mode
                        max-turns permission-mode resume continue?
                        partial-messages? extra-args]
                 :or   {claude-bin "claude"
                        output-format "json"
                        system-prompt-mode :append}}]
  (let [{:keys [instructions model tools]} agent
        sp-flag (if (= system-prompt-mode :replace)
                  "--system-prompt"
                  "--append-system-prompt")]
    (cond-> [claude-bin "-p" prompt "--output-format" output-format]
      ;; claude requires --verbose for stream-json under --print
      (= output-format "stream-json") (conj "--verbose")
      instructions      (into [sp-flag instructions])
      model             (into ["--model" model])
      (seq tools)       (into ["--allowedTools" (str/join "," tools)])
      max-turns         (into ["--max-turns" (str max-turns)])
      permission-mode   (into ["--permission-mode" (name permission-mode)])
      resume            (into ["--resume" (str resume)])
      continue?         (conj "--continue")
      partial-messages? (conj "--include-partial-messages")
      (seq extra-args)  (into (vec extra-args)))))

(defn result-map->result
  "Turn a parsed `claude -p` result object (keyword-keyed map — the JSON `result`
  payload, or the terminal `result` event in a stream) into a karcarthy result."
  [agent-name m]
  (k/result {:agent      agent-name
             :ok?        (not (true? (:is_error m)))
             :text       (:result m)
             :subtype    (:subtype m)          ; e.g. "success", "error_max_turns"
             :num-turns  (:num_turns m)
             :session-id (:session_id m)
             :cost-usd   (:total_cost_usd m)
             :usage      (:usage m)
             :raw        m}))

(defn parse-result
  "Parse the stdout of `claude -p --output-format json` (one JSON object) into a
  karcarthy result map. Exposed for unit testing against captured JSON."
  [agent-name stdout]
  (result-map->result agent-name (json/read-str stdout :key-fn keyword)))

(defn read-stream
  "Run `argv` as a subprocess, parse each stdout line as a JSON event (keyword
  keys), call `(on-event ev)` per event, and return
  {:events [...] :exit n :result <terminal \"result\" event map, or nil>}.
  stderr is merged into stdout; non-JSON lines are ignored. `opts`: :on-event
  :dir :timeout-ms (a watchdog force-kills the process after :timeout-ms).
  Exposed (and harness-agnostic) so it can be tested with any line-emitting
  command."
  [argv {:keys [on-event dir timeout-ms]}]
  (let [pb (doto (ProcessBuilder. ^java.util.List (vec argv))
             (.redirectErrorStream true))]
    (when dir (.directory pb (io/file (str dir))))
    (let [proc     (.start pb)
          watchdog (when timeout-ms
                     (future (Thread/sleep timeout-ms)
                             (when (.isAlive proc) (.destroyForcibly proc))))]
      (try
        (with-open [rdr (io/reader (.getInputStream proc))]
          (loop [events []]
            (if-let [line (.readLine ^java.io.BufferedReader rdr)]
              (let [ev (try (json/read-str line :key-fn keyword)
                            (catch Exception _ nil))]
                (when (and ev on-event) (on-event ev))
                (recur (if ev (conj events ev) events)))
              {:events events
               :exit   (.waitFor proc)
               :result (last (filter #(= "result" (:type %)) events))})))
        (finally
          (when watchdog (future-cancel watchdog)))))))

(defn- run-streaming
  "Stream `claude -p --output-format stream-json`, invoking (:on-event opts) per
  event, and return the karcarthy result built from the terminal result event."
  [agent prompt opts]
  (let [argv (claude-command agent prompt (assoc opts :output-format "stream-json"))
        {:keys [result exit events]} (read-stream argv (select-keys opts [:on-event :dir :timeout-ms]))]
    (if result
      (result-map->result (:name agent) result)
      (k/result {:agent (:name agent) :ok? false :text nil
                 :error (str "stream ended without a result event (exit " exit ")")
                 :raw   {:exit exit :events events :argv argv}}))))

(defn- run-buffered
  [agent prompt opts]
  (let [output-format (get opts :output-format "json")
        argv          (claude-command agent prompt opts)
        {:keys [exit out err timed-out?]}
        (proc/run argv {:dir (:dir opts) :timeout-ms (:timeout-ms opts)})]
    (cond
      timed-out?
      (k/result {:agent (:name agent) :ok? false :text nil
                 :error "claude timed out"
                 :raw   {:timed-out? true :out out :err err :argv argv}})

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
      (and exit (zero? exit))
      (k/result {:agent (:name agent) :ok? true
                 :text  out
                 :raw   {:out out :err err}})

      :else
      (k/result {:agent (:name agent) :ok? false
                 :text  (or (not-empty err) out)
                 :error (str "claude exited with status " exit)
                 :raw   {:exit exit :out out :err err :argv argv}}))))

(defn- run-claude
  "Dispatch to the streaming reader when an :on-event callback is supplied or
  :output-format is \"stream-json\"; otherwise run buffered."
  [agent prompt opts]
  (if (or (:on-event opts) (= "stream-json" (get opts :output-format)))
    (run-streaming agent prompt opts)
    (run-buffered agent prompt opts)))

(defn claude-harness
  "A `karcarthy.core/Harness` that drives `claude -p`. `default-opts` are merged
  beneath per-run opts (per-run wins). See `claude-command` for command-building
  option keys; additionally `:dir` sets the agent's working directory,
  `:timeout-ms` force-kills a hung run, and `:on-event` (a fn of each stream
  event) streams via --output-format stream-json.

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

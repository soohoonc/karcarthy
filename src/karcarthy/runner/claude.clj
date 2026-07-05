(ns karcarthy.runner.claude
  "Runner that drives the Claude CLI in
  headless (`-p`) mode. Each agent run is one `claude -p` invocation:

    claude -p <prompt> --output-format json
           --append-system-prompt <instructions>
           [--model M] [--allowedTools t1,t2] [--max-turns N] ...

  The command builder (`command`) is a pure function returning a vector
  of strings - the command is data too, so you can inspect or transform it
  before running."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [karcarthy.core :as k]
            [karcarthy.proc :as proc]))

(defn- option-name [x]
  (cond
    (keyword? x) (name x)
    (symbol? x) (name x)
    :else x))

(defn subagent-spec
  "Lower a karcarthy subagent to Claude Code's `--agents` JSON shape."
  [subagent]
  (when-not (k/subagent? subagent)
    (throw (ex-info "invalid Claude subagent" {:subagent subagent})))
  (cond-> {:description (:description subagent)
           :prompt      (:instructions subagent)}
    (:tools subagent) (assoc :tools (:tools subagent))
    (:disallowed-tools subagent) (assoc :disallowedTools (:disallowed-tools subagent))
    (:model subagent) (assoc :model (:model subagent))
    (:permission-mode subagent) (assoc :permissionMode (option-name (:permission-mode subagent)))
    (:mcp-servers subagent) (assoc :mcpServers (:mcp-servers subagent))
    (:hooks subagent) (assoc :hooks (:hooks subagent))
    (:max-turns subagent) (assoc :maxTurns (:max-turns subagent))
    (:skills subagent) (assoc :skills (:skills subagent))
    (:initial-prompt subagent) (assoc :initialPrompt (:initial-prompt subagent))
    (:memory subagent) (assoc :memory (option-name (:memory subagent)))
    (:effort subagent) (assoc :effort (option-name (:effort subagent)))
    (contains? subagent :background?) (assoc :background (:background? subagent))
    (:isolation subagent) (assoc :isolation (option-name (:isolation subagent)))
    (:color subagent) (assoc :color (option-name (:color subagent)))))

(defn subagents-json
  "Return the JSON object accepted by Claude Code's `--agents` flag."
  [subagents]
  (let [names      (map :name subagents)
        duplicates (->> names frequencies (filter (fn [[_ n]] (> n 1))) (map first) vec)]
    (when (seq duplicates)
      (throw (ex-info "duplicate Claude subagent names"
                      {:duplicates duplicates})))
    (json/write-str
     (into {}
           (map (fn [subagent]
                  [(:name subagent) (subagent-spec subagent)]))
           subagents))))

(defn command
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
    :subagents           vector of k/subagent values for Claude --agents
    :prompt-via          :argv (default) passes the prompt as an argument;
                         :stdin omits it from the argv and the runner pipes it
                         to the process instead (`claude -p` reads stdin when
                         no positional prompt is given). Use :stdin for prompts
                         that can exceed OS argv limits, e.g. reduce fan-in.
    :extra-args          vector of strings appended verbatim (escape hatch for
                         --add-dir, --mcp-config, --disallowedTools, ...)

  Agent fields used: :instructions, :model, :tools (-> --allowedTools)."
  [agent prompt {:keys [claude-bin output-format system-prompt-mode
                        max-turns permission-mode resume continue?
                        partial-messages? subagents extra-args prompt-via model]
                 :or   {claude-bin "claude"
                        output-format "json"
                        system-prompt-mode :append
                        prompt-via :argv}}]
  (let [{agent-model :model :keys [instructions tools]} agent
        model (or model agent-model)
        sp-flag (if (= system-prompt-mode :replace)
                  "--system-prompt"
                  "--append-system-prompt")]
    (cond-> (if (= prompt-via :stdin)
              [claude-bin "-p" "--output-format" output-format]
              [claude-bin "-p" prompt "--output-format" output-format])
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
      (seq subagents)   (into ["--agents" (subagents-json subagents)])
      (seq extra-args)  (into (vec extra-args)))))

(defn payload->result
  "Turn a parsed `claude -p` result object (keyword-keyed map - the JSON `result`
  payload, or the terminal `result` event in a stream) into a karcarthy result."
  [agent-name m]
  (let [status (:is_error m)]
    (k/result (cond-> {:agent      agent-name
                       :ok?        (and (boolean? status) (not status))
                       :text       (:result m)
                       :subtype    (:subtype m) ; e.g. "success", "error_max_turns"
                       :num-turns  (:num_turns m)
                       :session-id (:session_id m)
                       :cost-usd   (:total_cost_usd m)
                       :usage      (:usage m)
                       :raw        m}
                (not (boolean? status))
                (assoc :error "claude result is missing Boolean is_error")))))

(defn stdout->result
  "Parse the stdout of `claude -p --output-format json` (one JSON object) into a
  karcarthy result map. Exposed for unit testing against captured JSON."
  [agent-name stdout]
  (payload->result agent-name (json/read-str stdout :key-fn keyword)))

(defn stream!
  "Run `argv` as a subprocess, parse each stdout line as a JSON event (keyword
  keys), call `(on-event ev)` per event, and return
  {:events [...] :exit n :result <terminal \"result\" event map, or nil>}.
  stderr is merged into stdout; non-JSON lines are ignored. `opts`: :on-event
  :dir :timeout-ms (a watchdog force-kills the process after :timeout-ms),
  :in (a string written to the process's stdin, then closed - used by
  :prompt-via :stdin). Exposed (and runner-agnostic) so it can be tested with
  any line-emitting command."
  [argv {:keys [on-event dir timeout-ms in]}]
  (let [pb (doto (ProcessBuilder. ^java.util.List (vec argv))
             (.redirectErrorStream true))]
    (when dir (.directory pb (io/file (str dir))))
    (let [process    (.start pb)
          timed-out? (atom false)
          watchdog (when timeout-ms
                     (future (Thread/sleep timeout-ms)
                             (when (.isAlive process)
                               (reset! timed-out? true)
                               (proc/terminate! process))))
          stdin-f   (future (proc/write-stdin! process in))
          events-f  (future
                      (with-open [rdr (io/reader (.getInputStream process))]
                        (loop [events []]
                          (if-let [line (.readLine ^java.io.BufferedReader rdr)]
                            (let [ev (try (json/read-str line :key-fn keyword)
                                          (catch Exception _ nil))]
                              (when (and ev on-event) (on-event ev))
                              (recur (if ev (conj events ev) events)))
                            events))))]
      (try
        (let [events (try
                       @events-f
                       (catch Throwable t
                         (if @timed-out? [] (throw t))))]
          {:events events
           :exit   (.waitFor process)
           :timed-out? @timed-out?
           :result (last (filter #(= "result" (:type %)) events))})
        (finally
          (future-cancel stdin-f)
          (future-cancel events-f)
          (when watchdog (future-cancel watchdog))
          (when (.isAlive process) (proc/terminate! process)))))))

(defn- run-streaming!
  "Stream `claude -p --output-format stream-json`, invoking (:on-event opts) per
  event, and return the karcarthy result built from the terminal result event."
  [agent prompt opts]
  (let [argv        (command agent prompt (assoc opts :output-format "stream-json"))
        stream-opts (cond-> (select-keys opts [:on-event :dir :timeout-ms])
                      (= :stdin (:prompt-via opts)) (assoc :in prompt))
        {:keys [result exit events timed-out?]} (stream! argv stream-opts)
        parsed (when result (payload->result (:name agent) result))]
    (cond
      timed-out?
      (k/result {:agent (:name agent) :ok? false :text (:text parsed)
                 :error "claude timed out"
                 :raw {:runner :claude :exit exit :events events :argv argv}})

      (and parsed (zero? exit))
      parsed

      parsed
      (k/result (assoc parsed
                       :ok? false
                       :error (str "claude exited with status " exit)
                       :raw {:runner :claude :exit exit :events events
                             :payload (:raw parsed) :argv argv}))

      :else
      (k/result {:agent (:name agent) :ok? false :text nil
                 :error (str "stream ended without a result event (exit " exit ")")
                 :raw   {:runner :claude :exit exit :events events :argv argv}}))))

(defn- run-buffered!
  [agent prompt opts]
  (let [output-format (get opts :output-format "json")
        argv          (command agent prompt opts)
        {:keys [exit out err timed-out?]}
        (proc/run argv {:dir        (:dir opts)
                        :timeout-ms (:timeout-ms opts)
                        :in         (when (= :stdin (:prompt-via opts)) prompt)})
        raw {:runner :claude :exit exit :out out :err err
             :timed-out? timed-out? :argv argv}]
    (cond
      timed-out?
      (k/result {:agent (:name agent) :ok? false :text nil
                 :error "claude timed out"
                 :raw   raw})

      ;; Preserve structured output for diagnostics, but process failure wins.
      (and (= output-format "json") (seq (str/trim (or out ""))))
      (try
        (let [parsed (stdout->result (:name agent) out)]
          (if (zero? exit)
            parsed
            (k/result (assoc parsed
                             :ok? false
                             :error (str "claude exited with status " exit)
                             :raw {:payload (:raw parsed) :process raw}))))
        (catch Exception e
          (k/result {:agent (:name agent) :ok? false
                     :text  (or (not-empty err) out)
                     :error (str "could not parse claude JSON: " (.getMessage e))
                     :raw   raw})))

      ;; Non-JSON output (or empty stdout): trust the exit code.
      (and exit (zero? exit))
      (k/result {:agent (:name agent) :ok? true
                 :text  out
                 :raw   raw})

      :else
      (k/result {:agent (:name agent) :ok? false
                 :text  (or (not-empty err) out)
                 :error (str "claude exited with status " exit)
                 :raw   raw}))))

(defn- invoke!
  "Dispatch to the streaming reader when an :on-event callback is supplied or
  :output-format is \"stream-json\"; otherwise run buffered."
  [agent prompt opts]
  (if (or (:on-event opts) (= "stream-json" (get opts :output-format)))
    (run-streaming! agent prompt opts)
    (run-buffered! agent prompt opts)))

(defn claude-runner
  "Runner for Claude. `default-options` are merged beneath per-run options
  (per-run wins). See `command` for command-building
  option keys; additionally `:dir` sets the agent's working directory,
  `:timeout-ms` force-kills a hung run, and `:on-event` (a fn of each stream
  event) streams via --output-format stream-json."
  ([] (claude-runner {}))
  ([default-options]
   (reify k/Runner
     (-run [_ agent prompt opts]
       (invoke! agent prompt (merge default-options opts))))))

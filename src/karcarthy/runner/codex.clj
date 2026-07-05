(ns karcarthy.runner.codex
  "Runner and lowering helpers for Codex.

  Codex custom agents are file-backed TOML definitions under `.codex/agents/`
  or `~/.codex/agents/`. The config helpers return maps that can be rendered by
  a caller; they do not write files.

  `codex-runner` drives Codex CLI non-interactive mode (`codex exec`)."
  (:require [karcarthy.core :as k]
            [karcarthy.proc :as proc]))

(defn- option-name [x]
  (cond
    (keyword? x) (name x)
    (symbol? x) (name x)
    :else x))

(defn subagent->config
  "Lower a karcarthy subagent to Codex custom-agent config keys."
  [subagent]
  (when-not (k/subagent? subagent)
    (throw (ex-info "invalid Codex subagent" {:subagent subagent})))
  (cond-> (merge (:config subagent)
                 {:name                   (:name subagent)
                  :description            (:description subagent)
                  :developer_instructions (:instructions subagent)})
    (:nicknames subagent) (assoc :nickname_candidates (:nicknames subagent))
    (:model subagent) (assoc :model (:model subagent))
    (or (:reasoning-effort subagent) (:effort subagent))
    (assoc :model_reasoning_effort
           (option-name (or (:reasoning-effort subagent) (:effort subagent))))
    (:sandbox-mode subagent) (assoc :sandbox_mode (option-name (:sandbox-mode subagent)))
    (:mcp-servers subagent) (assoc :mcp_servers (:mcp-servers subagent))))

(defn agents->config
  "Build Codex global `[agents]` configuration keys."
  [& {:keys [max-threads max-depth job-max-runtime-seconds]}]
  (cond-> {}
    max-threads (assoc :max_threads max-threads)
    max-depth (assoc :max_depth max-depth)
    job-max-runtime-seconds (assoc :job_max_runtime_seconds job-max-runtime-seconds)))

(defn prompt
  "Build the full prompt text for one karcarthy leaf agent, including the
  flowing workflow input.

  `codex-runner` sends this on stdin and passes `-` as the argv prompt:
  `codex exec` reads the prompt from stdin only when the positional prompt is
  absent or `-`, so a prompt argv plus piped input would silently drop the
  input. Stdin also avoids OS argv-size limits for large fan-in prompts."
  [agent input]
  (str "You are running as one karcarthy leaf agent.\n\n"
       "Agent name: " (:name agent) "\n\n"
       "Instructions:\n" (:instructions agent) "\n\n"
       "Return only the output requested by the instructions.\n\n"
       "Input:\n" input))

(defn command
  "Pure: build the argv for `codex exec`. The prompt itself travels on stdin
  (see `prompt`), so the argv ends with `-`.

  Options:
    :codex-bin              CLI executable (default \"codex\")
    :dir / :cwd             working directory for the run
    :model                  default model override
    :sandbox                sandbox mode, e.g. :read-only or :workspace-write
    :color                  color mode, default :never
    :ephemeral?             pass --ephemeral, default true
    :skip-git-repo-check?   pass --skip-git-repo-check, default true
    :extra-args             vector of strings appended before the prompt

  Agent fields used: :model."
  [agent {:keys [codex-bin dir cwd model sandbox color ephemeral?
                 skip-git-repo-check? extra-args]
          :or   {codex-bin "codex"
                 sandbox :read-only
                 color :never
                 ephemeral? true
                 skip-git-repo-check? true}}]
  (let [workdir (or dir cwd)
        model   (or model (:model agent))]
    (cond-> [codex-bin "exec"]
      ephemeral? (conj "--ephemeral")
      color (into ["--color" (option-name color)])
      sandbox (into ["--sandbox" (option-name sandbox)])
      skip-git-repo-check? (conj "--skip-git-repo-check")
      workdir (into ["--cd" (str workdir)])
      model (into ["--model" model])
      (seq extra-args) (into (vec extra-args))
      true (conj "-"))))

(defn codex-runner
  "Runner for Codex. `default-options` are merged beneath per-run options.
  This implementation drives `codex exec`; see `command` for command-building
  option keys.

  Additional options:
    :env         extra environment variables
    :timeout-ms  kill the subprocess after this many milliseconds
    :trim?       trim stdout before returning result text, default true"
  ([] (codex-runner {}))
  ([default-options]
   (reify k/Runner
     (-run [_ agent input opts]
       (k/reject-tools! :codex agent)
       (let [opts (merge {:trim? true} default-options opts)
             argv (command agent opts)]
         (proc/->result (proc/run argv {:in         (prompt agent input)
                                        :dir        (or (:dir opts) (:cwd opts))
                                        :env        (:env opts)
                                        :timeout-ms (:timeout-ms opts)})
                        {:agent (:name agent) :label "codex" :trim? (:trim? opts)
                         :raw   {:runner :codex :argv argv}}))))))

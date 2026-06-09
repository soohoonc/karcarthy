(ns karcarthy.runner.codex
  "Pure lowering helpers for Codex custom agent configuration.

  Codex custom agents are file-backed TOML definitions under `.codex/agents/`
  or `~/.codex/agents/`. This namespace returns config maps that can be rendered
  by a caller or a future dedicated Codex runner; it does not write files."
  (:require [karcarthy.core :as k]))

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

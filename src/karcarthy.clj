(ns karcarthy
  "A convenience facade: the common karcarthy API re-exported under one
  namespace, so you can use a single alias instead of juggling several:

      (require '[karcarthy :as k])
      (k/run (k/claude-cli {}) (k/chain a b) \"hi\")

  The canonical homes are still `karcarthy.core`, `karcarthy.orchestrate`,
  `karcarthy.session`, `karcarthy.self`, and the implementation adapters;
  this namespace only forwards to them."
  (:refer-clojure :exclude [agent])
  (:require [karcarthy.core]
            [karcarthy.orchestrate]
            [karcarthy.patterns]
            [karcarthy.session]
            [karcarthy.self]
            [karcarthy.dynamic]
            [karcarthy.runner.claude]
            [karcarthy.runner.command]
            [karcarthy.runner.openai]
            [karcarthy.harness.claude]
            [karcarthy.harness.command]
            [karcarthy.harness.openai]))

(defmacro ^:private export
  "Re-export the var named by the fully-qualified symbol `qsym` into this
  namespace under its short name, preserving :doc and :arglists. Macros are
  re-exported as forwarding macros."
  [qsym]
  (let [v (resolve qsym)]
    (when (nil? v)
      (throw (ex-info (str "export: cannot resolve " qsym) {:sym qsym})))
    (let [m  (meta v)
          nm (symbol (name qsym))]
      (if (:macro m)
        `(defmacro ~nm [~'& args#] (cons '~qsym args#))
        ;; copy :doc/:arglists as quoted data via alter-meta! (putting :arglists
        ;; as symbol metadata would make the compiler evaluate the arglist).
        `(do (def ~nm @(var ~qsym))
             (alter-meta! (var ~nm) merge '~(select-keys m [:doc :arglists]))
             (var ~nm))))))

;; data model + mock adapter
(export karcarthy.core/agent)
(export karcarthy.core/agent?)
(export karcarthy.core/defagent)
(export karcarthy.core/Adapter)
(export karcarthy.core/Runner)
(export karcarthy.core/Harness)
(export karcarthy.core/resolve-adapter)
(export karcarthy.core/resolve-runner)
(export karcarthy.core/resolve-harness)
(export karcarthy.core/run-agent)
(export karcarthy.core/mock-adapter)
(export karcarthy.core/mock-runner)
(export karcarthy.core/mock-harness)
(export karcarthy.core/result)
(export karcarthy.core/ok?)

;; orchestration
(export karcarthy.orchestrate/chain)
(export karcarthy.orchestrate/parallel)
(export karcarthy.orchestrate/parallel*)
(export karcarthy.orchestrate/route)
(export karcarthy.orchestrate/refine)
(export karcarthy.orchestrate/orchestrate)
(export karcarthy.orchestrate/handoff)
(export karcarthy.orchestrate/run)
(export karcarthy.orchestrate/run-flow)
(export karcarthy.orchestrate/workflow?)
(export karcarthy.orchestrate/flow?)
(export karcarthy.orchestrate/defworkflow)
(export karcarthy.orchestrate/defflow)

;; common orchestrator pattern helpers
(export karcarthy.patterns/task-agent)
(export karcarthy.patterns/crew)
(export karcarthy.patterns/group-chat)
(export karcarthy.patterns/workflow-agent)
(export karcarthy.patterns/handoff-router)
(export karcarthy.patterns/state-graph)

;; sessions
(export karcarthy.session/converse)

;; agents using the language themselves
(export karcarthy.self/evolve)
(export karcarthy.self/registry)
(export karcarthy.self/agent-ref)
(export karcarthy.self/read-workflow)
(export karcarthy.self/dsl-reference)

;; dynamic data-level execution
(export karcarthy.dynamic/dynamic-runtime)
(export karcarthy.dynamic/dynamic-agent-ref)
(export karcarthy.dynamic/dynamic-workflow-ref)
(export karcarthy.dynamic/workflow-config?)
(export karcarthy.dynamic/materialize)
(export karcarthy.dynamic/read-operation)
(export karcarthy.dynamic/apply-operation)
(export karcarthy.dynamic/run-dynamic)
(export karcarthy.dynamic/dynamic-reference)

;; Agent SDK/CLI adapters, followed by legacy aliases
(export karcarthy.runner.claude/claude-cli)
(export karcarthy.runner.claude/claude-runner)
(export karcarthy.runner.command/command-adapter)
(export karcarthy.runner.command/command-runner)
(export karcarthy.runner.openai/openai-agents-sdk)
(export karcarthy.runner.openai/openai-agents-runner)

;; deprecated compatibility names
(export karcarthy.harness.claude/claude-harness)
(export karcarthy.harness.command/command-harness)
(export karcarthy.harness.openai/openai-agents-harness)

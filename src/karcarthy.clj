(ns karcarthy
  "A convenience facade: the common karcarthy API re-exported under one
  namespace, so you can use a single alias instead of juggling several:

      (require '[karcarthy :as k])
      (k/run (k/claude-cli {}) (k/pipe a b) \"hi\")

  The canonical homes are still `karcarthy.core`, `karcarthy.orchestrate`,
  `karcarthy.session`, `karcarthy.self`, and the implementation adapters;
  this namespace only forwards to them."
  (:refer-clojure :exclude [agent iterate map reduce])
  (:require [karcarthy.core]
            [karcarthy.orchestrate]
            [karcarthy.patterns]
            [karcarthy.session]
            [karcarthy.self]
            [karcarthy.dynamic]
            [karcarthy.runner.claude]
            [karcarthy.runner.command]
            [karcarthy.runner.openai]))

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
(export karcarthy.core/explain-agent)
(export karcarthy.core/defagent)
(export karcarthy.core/Adapter)
(export karcarthy.core/resolve-adapter)
(export karcarthy.core/run-agent)
(export karcarthy.core/mock-adapter)
(export karcarthy.core/result)
(export karcarthy.core/ok?)

;; orchestration
(export karcarthy.orchestrate/pipe)
(export karcarthy.orchestrate/map)
(export karcarthy.orchestrate/reduce)
(export karcarthy.orchestrate/iterate)
(export karcarthy.orchestrate/bind)
(export karcarthy.orchestrate/run)
(export karcarthy.orchestrate/workflow?)
(export karcarthy.orchestrate/defworkflow)

;; common orchestrator pattern helpers
(export karcarthy.patterns/task-agent)
(export karcarthy.patterns/crew)
(export karcarthy.patterns/group-chat)
(export karcarthy.patterns/workflow-agent)
(export karcarthy.patterns/state-graph)

;; sessions
(export karcarthy.session/converse)

;; agents using the language themselves
(export karcarthy.self/evolve)
(export karcarthy.self/registry)
(export karcarthy.self/agent-ref)
(export karcarthy.self/read-workflow)
(export karcarthy.self/read-agent)
(export karcarthy.self/dsl-reference)

;; runtime data helpers
(export karcarthy.dynamic/dynamic-runtime)
(export karcarthy.dynamic/dynamic-agent-ref)
(export karcarthy.dynamic/dynamic-workflow-ref)
(export karcarthy.dynamic/workflow-config?)
(export karcarthy.dynamic/materialize)
(export karcarthy.dynamic/read-operation)
(export karcarthy.dynamic/apply-operation)

;; Agent SDK/CLI adapters
(export karcarthy.runner.claude/claude-cli)
(export karcarthy.runner.command/command-adapter)
(export karcarthy.runner.openai/openai-agents-sdk)

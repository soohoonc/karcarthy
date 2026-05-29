(ns karcarthy
  "A convenience facade: the common karcarthy API re-exported under one
  namespace, so you can use a single alias instead of juggling several:

      (require '[karcarthy :as k])
      (k/run (k/claude-runner {}) (k/chain a b) \"hi\")

  The canonical homes are still `karcarthy.core`, `karcarthy.orchestrate`,
  `karcarthy.session`, `karcarthy.self`, and the `karcarthy.runner.*` adapters;
  this namespace only forwards to them."
  (:refer-clojure :exclude [agent])
  (:require [karcarthy.core]
            [karcarthy.orchestrate]
            [karcarthy.session]
            [karcarthy.self]
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

;; data model + mock runner
(export karcarthy.core/agent)
(export karcarthy.core/agent?)
(export karcarthy.core/defagent)
(export karcarthy.core/Runner)
(export karcarthy.core/Harness)
(export karcarthy.core/resolve-runner)
(export karcarthy.core/resolve-harness)
(export karcarthy.core/run-agent)
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

;; sessions
(export karcarthy.session/converse)

;; agents using the language themselves
(export karcarthy.self/run-authored)
(export karcarthy.self/evolve)
(export karcarthy.self/registry)
(export karcarthy.self/agent-ref)
(export karcarthy.self/read-workflow)
(export karcarthy.self/read-flow)
(export karcarthy.self/dsl-reference)

;; runner adapters
(export karcarthy.runner.claude/claude-runner)
(export karcarthy.runner.command/command-runner)
(export karcarthy.runner.openai/openai-agents-runner)

;; deprecated compatibility names
(export karcarthy.harness.claude/claude-harness)
(export karcarthy.harness.command/command-harness)
(export karcarthy.harness.openai/openai-agents-harness)

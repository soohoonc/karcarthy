(ns karcarthy
  "A convenience facade: the common karcarthy API re-exported under one
  namespace, so you can use a single alias instead of juggling several:

      (require '[karcarthy :as k])
      (k/run (k/claude-cli-runner {}) (k/pipe a b) \"hi\")

  The canonical homes are still `karcarthy.core`, `karcarthy.orchestrate`,
  `karcarthy.self`, and the implementation runners; this namespace only
  forwards the public surface."
  (:refer-clojure :exclude [agent map iterate reduce])
  (:require [karcarthy.core]
            [karcarthy.orchestrate]
            [karcarthy.rewrite]
            [karcarthy.schema]
            [karcarthy.self]
            [karcarthy.runner.claude]
            [karcarthy.runner.process]
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

;; data model + mock runner
(export karcarthy.core/agent)
(export karcarthy.core/agent?)
(export karcarthy.core/explain-agent)
(export karcarthy.core/defagent)
(export karcarthy.core/mock-runner)
(export karcarthy.core/fn-runner)
(export karcarthy.core/result)
(export karcarthy.core/ok?)

;; orchestration
(export karcarthy.orchestrate/pipe)
(export karcarthy.orchestrate/branch)
(export karcarthy.orchestrate/delegate)
(export karcarthy.orchestrate/reduce)
(export karcarthy.orchestrate/revise)
(export karcarthy.orchestrate/route)
(export karcarthy.orchestrate/continue)
(export karcarthy.orchestrate/dynamic)
(export karcarthy.orchestrate/agent-ref)
(export karcarthy.orchestrate/workflow-ref)
(export karcarthy.orchestrate/run)
(export karcarthy.orchestrate/workflow?)
(export karcarthy.orchestrate/defworkflow)

;; schema reference values
(export karcarthy.schema/edn-schema)
(export karcarthy.schema/json-schema)

;; structural workflow rewrites
(export karcarthy.rewrite/agents)
(export karcarthy.rewrite/over)
(export karcarthy.rewrite/configure)

;; parsing generated agent/workflow data
(export karcarthy.self/read-workflow)
(export karcarthy.self/read-agent)
(export karcarthy.self/dsl-reference)

;; Agent SDK/CLI runners
(export karcarthy.runner.claude/claude-cli-runner)
(export karcarthy.runner.process/process-runner)
(export karcarthy.runner.process/shell-runner)
(export karcarthy.runner.openai/openai-agents-runner)

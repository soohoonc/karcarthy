(ns karcarthy.runner.claude
  "Claude CLI adapter namespace. The old
  `karcarthy.harness.claude` namespace remains available for compatibility."
  (:require [karcarthy.harness.claude :as claude]))

(def claude-command claude/claude-command)
(def result-map->result claude/result-map->result)
(def parse-result claude/parse-result)
(def read-stream claude/read-stream)
(def claude-cli claude/claude-cli)
(def claude-runner claude/claude-runner)

(doseq [[dst src] [[#'claude-command #'claude/claude-command]
                   [#'result-map->result #'claude/result-map->result]
                   [#'parse-result #'claude/parse-result]
                   [#'read-stream #'claude/read-stream]
                   [#'claude-cli #'claude/claude-cli]
                   [#'claude-runner #'claude/claude-runner]]]
  (alter-meta! dst merge (select-keys (meta src) [:doc :arglists])))

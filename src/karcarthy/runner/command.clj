(ns karcarthy.runner.command
  "Command adapter namespace. The old
  `karcarthy.harness.command` namespace remains available for compatibility."
  (:require [karcarthy.harness.command :as command]))

(def command-adapter command/command-adapter)
(def command-runner command/command-runner)

(doseq [[dst src] [[#'command-adapter #'command/command-adapter]
                   [#'command-runner #'command/command-runner]]]
  (alter-meta! dst merge (select-keys (meta src) [:doc :arglists])))

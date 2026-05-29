(ns karcarthy.runner.command
  "Preferred namespace for the command runner. The old
  `karcarthy.harness.command` namespace remains available for compatibility."
  (:require [karcarthy.harness.command :as command]))

(def command-runner command/command-runner)

(doseq [[dst src] [[#'command-runner #'command/command-runner]]]
  (alter-meta! dst merge (select-keys (meta src) [:doc :arglists])))

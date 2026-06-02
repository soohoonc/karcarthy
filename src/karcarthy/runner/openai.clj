(ns karcarthy.runner.openai
  "OpenAI Agents SDK adapter namespace. The old
  `karcarthy.harness.openai` namespace remains available for compatibility."
  (:require [karcarthy.harness.openai :as openai]))

(def openai-request openai/openai-request)
(def parse-openai-result openai/parse-openai-result)
(def openai-agents-sdk openai/openai-agents-sdk)
(def openai-agents-runner openai/openai-agents-runner)

(doseq [[dst src] [[#'openai-request #'openai/openai-request]
                   [#'parse-openai-result #'openai/parse-openai-result]
                   [#'openai-agents-sdk #'openai/openai-agents-sdk]
                   [#'openai-agents-runner #'openai/openai-agents-runner]]]
  (alter-meta! dst merge (select-keys (meta src) [:doc :arglists])))

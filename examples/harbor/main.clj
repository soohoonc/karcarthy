(ns main
  "Fixed Coding Agent exposed to Harbor through ACP."
  (:require [clojure.string :as str]
            [karcarthy :as k]))

(def instructions
  (str
   "Work autonomously on the repository in your tool root. "
   "Inspect the repository and run its tests before deciding what to change. "
   "Diagnose causes rather than patching symptoms, make focused edits, and run "
   "the relevant tests again before answering. "
   "Use additional Agents, parallel work, review, or other strategies when the "
   "task evidence makes them useful; choose the approach yourself. "
   "Finish with a concise account of the cause, changes, and verification."))

(defn model-id [selected]
  (or selected
      (System/getenv "KARCARTHY_OPENAI_MODEL")
      "gpt-5.6"))

(defn model [context]
  {:transport :responses
   :provider :openai
   :id (model-id (:model-id context))
   :reasoning :medium
   :timeout-ms 300000})

(defn harbor-agent [{:keys [cwd] :as context}]
  (when (str/blank? cwd)
    (throw (ex-info "Harbor Agent context requires :cwd" {})))
  (k/agent
   {:name "coding-agent"
    :description "Inspect, modify, and verify an unfamiliar repository."
    :model (model context)
    :instructions instructions
    :tools (conj (k/local-tools {:cwd cwd}) k/eval)
    :input-schema string?
    :output-schema string?
    :max-turns 24}))

(ns example.coding
  "Open-ended live Coding Agent."
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

(defn coding-agent [{:keys [cwd] :as context}]
  (k/agent
   {:name "coding-agent"
    :description "Inspect, modify, and verify an unfamiliar repository."
    :model {:transport :responses
            :provider :openai
            :id (model-id (:model-id context))
            :reasoning :medium
            :timeout-ms 300000}
    :instructions instructions
    :tools (conj (k/local-tools {:cwd cwd}) k/eval)
    :input-schema string?
    :output-schema string?
    :max-turns 24}))

(defn run-coding! [cwd task]
  (k/run! (coding-agent {:cwd cwd}) task
          {:limits {:model-calls 32
                    :evals 4
                    :depth 3
                    :deadline-ms 600000}}))

(defn -main [& args]
  (let [[cwd & task-words] args]
    (when (or (str/blank? cwd) (empty? task-words))
      (binding [*out* *err*]
        (println "Usage: clojure -M:examples coding <directory> <task>"))
      (System/exit 2))
    (let [run (run-coding! cwd (str/join " " task-words))]
      (if (= :completed (:status run))
        (println (:output run))
        (do
          (binding [*out* *err*]
            (println "Run failed:" (get-in run [:error :message])))
          (System/exit 1))))))

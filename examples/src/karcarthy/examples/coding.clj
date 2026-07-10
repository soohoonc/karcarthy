(ns karcarthy.examples.coding
  "Live coding Agent used directly and through ACP/Harbor."
  (:require [clojure.string :as str]
            [karcarthy :as k]))

(def base-instructions
  (str
   "Work autonomously on the repository in your tool root. "
   "Inspect the repository and run its tests before deciding what to change. "
   "Diagnose causes rather than patching symptoms, make focused edits, and run "
   "the relevant tests again before answering. "
   "Finish with a concise account of the cause, changes, and verification."))

(def specialist-instructions
  (str base-instructions " "
       "After your initial inspection and before editing, use the agent Tool "
       "once to create a focused specialist whose assignment and input are "
       "based on the evidence you found. Give that specialist the model and "
       "repository Tools it needs. Use its findings when deciding on the fix."))

(defn model-id [selected]
  (or selected
      (System/getenv "KARCARTHY_OPENAI_MODEL")
      "gpt-5.6"))

(defn specialist? [context]
  (if (contains? context :specialist?)
    (boolean (:specialist? context))
    (not= "0" (System/getenv "KARCARTHY_SPECIALIST"))))

(defn coding-agent [{:keys [cwd] :as context}]
  (k/agent
   {:name "coding-agent"
    :description "Inspect, modify, and verify an unfamiliar repository."
    :model {:transport :responses
            :provider :openai
            :id (model-id (:model-id context))
            :reasoning :medium
            :timeout-ms 300000}
    :instructions (if (specialist? context)
                    specialist-instructions
                    base-instructions)
    :tools (k/local-tools {:cwd cwd})
    :input string?
    :output string?
    :max-turns 24}))

(def harbor-agent coding-agent)

(defn run-coding! [cwd task]
  (k/run! (coding-agent {:cwd cwd}) task
          {:limits {:model-calls 32
                    :agent-forms 4
                    :depth 3
                    :parallelism 3
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

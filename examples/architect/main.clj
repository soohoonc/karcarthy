(ns example.architect
  "Live trace of an Agent choosing and running a task-specific team."
  (:require [clojure.string :as str]
            [karcarthy :as k]))

(defn model-id []
  (or (System/getenv "KARCARTHY_OPENAI_MODEL") "gpt-5.6"))

(defn instructions []
  (str
   "Solve the task by choosing a small team after you read it. "
   "Before answering, call eval exactly once. Write one Clojure expression that "
   "creates two or three specialist Agents whose unique kebab-case names and "
   "instructions fit this specific task. Do not use a predefined set of roles. "
   "Configure each specialist "
   "with model \"" (model-id) "\", :input-schema string?, and :output-schema string?. "
   "Tell each specialist to analyze one distinct part of the task and to avoid eval "
   "or delegation. Run every specialist exactly once and concurrently with future, "
   "passing input to each one. Dereference their Run maps and return their names and "
   ":output values as model-safe data. After eval returns, synthesize the evidence "
   "into one concise answer. Do not call a specialist again."))

(defn architect []
  (k/agent
   {:name "architect"
    :model {:transport :responses
            :provider :openai
            :id (model-id)
            :reasoning :low
            :timeout-ms 180000}
    :instructions (instructions)
    :input-schema string?
    :output-schema string?
    :max-turns 4}))

(defn credentials? []
  (or (not (str/blank? (System/getenv "RESPONSES_API_KEY")))
      (not (str/blank? (System/getenv "OPENAI_API_KEY")))))

(defn run-architect!
  ([task]
   (run-architect! task (k/monitor {:display :tree})))
  ([task monitor]
   (k/run! (architect) task
           {:on-event monitor
            :limits {:model-calls 8
                     :evals 1
                     :depth 1
                     :deadline-ms 240000}})))

(def default-task
  (str "A moon garden's leaves are turning silver after a solar storm. "
       "Diagnose the likely causes and recommend the next three checks."))

(defn -main [& words]
  (when-not (credentials?)
    (binding [*out* *err*]
      (println "Set RESPONSES_API_KEY or OPENAI_API_KEY to run this live example."))
    (System/exit 2))
  (let [task (if (seq words) (str/join " " words) default-task)
        run (run-architect! task)]
    (println)
    (if (= :completed (:status run))
      (do
        (println "ANSWER")
        (println (:output run)))
      (do
        (binding [*out* *err*]
          (println "Run failed:" (get-in run [:error :message])))
        (System/exit 1)))))

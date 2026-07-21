(ns example.architect
  "Live trace of one Agent writing and running two more Agents."
  (:require [clojure.string :as str]
            [karcarthy :as k]))

(defn model-id []
  (or (System/getenv "KARCARTHY_OPENAI_MODEL") "gpt-5.6"))

(defn instructions []
  (str
   "You are the parent Agent in a live demonstration. "
   "Before answering, call the built-in eval Tool once. Write one Clojure "
   "expression that creates a failure analyst and a rollout planner, runs both "
   "with future, dereferences the Runs, and returns their :output values. "
   "Use input as the task for both. Configure each with model \"" (model-id) "\", "
   ":input string?, :output string?, and instructions that forbid further "
   "delegation. Then synthesize the two returned results concisely."))

(defn architect []
  (k/agent
   {:name "architect"
    :model {:transport :responses
            :provider :openai
            :id (model-id)
            :reasoning :low
            :timeout-ms 180000}
    :instructions (instructions)
    :input string?
    :output string?
    :max-turns 4}))

(defn credentials? []
  (or (not (str/blank? (System/getenv "RESPONSES_API_KEY")))
      (not (str/blank? (System/getenv "OPENAI_API_KEY")))))

(defn run-architect!
  ([task]
   (run-architect! task (k/monitor {:display :tree})))
  ([task monitor]
   (k/run! (architect) task
           {:observe monitor
            :limits {:model-calls 4
                     :evals 1
                     :depth 2
                     :parallelism 3
                     :deadline-ms 240000}})))

(def default-task
  "Review a migration from synchronous writes to a queue.")

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

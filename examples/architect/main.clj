(ns example.architect
  "Live trace of Agents recursively writing and running more Agents."
  (:require [clojure.string :as str]
            [karcarthy :as k]))

(defn model-id []
  (or (System/getenv "KARCARTHY_OPENAI_MODEL") "gpt-5.6"))

(defn instructions []
  (str
   "You are the root Agent in a live recursive demonstration. "
   "Before answering, call eval exactly once. Write one Clojure expression "
   "that creates an Agent named coordinator and runs it with input. "
   "Configure coordinator with model \"" (model-id) "\", :input-schema string?, "
   ":output-schema string?, and instructions to call eval exactly once before answering. "
   "That second expression must create Agents named failure-analyst and "
   "rollout-planner, run each exactly once and concurrently with future on its "
   "input, dereference their Runs, and return their :output values as data. "
   "Configure both specialists with model \"" (model-id) "\", :input-schema string?, "
   ":output-schema string?, and focused instructions that forbid eval or delegation. "
   "After eval returns, coordinator must synthesize the two values itself without "
   "running either specialist again. Return the coordinator's result."))

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
                     :evals 2
                     :depth 2
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

(ns example.architect
  "Live trace of one Agent writing and running two more Agents."
  (:require [clojure.string :as str]
            [karcarthy :as k]))

(def instructions
  (str
   "You are the parent Agent in a live demonstration. "
   "Before answering, you must call the built-in agent Tool exactly twice. "
   "Submit both calls together so the Agents can run concurrently. "
   "Create one failure analyst that finds a non-obvious risk and one rollout "
   "planner that proposes a concrete safe plan. Give each Agent the complete "
   "user task as its explicit input. Keep each Agent definition focused and "
   "concise, then synthesize both results in a concise final answer."))

(defn model-id []
  (or (System/getenv "KARCARTHY_OPENAI_MODEL") "gpt-5.6"))

(defn architect []
  (k/agent
   {:name "architect"
    :model {:transport :responses
            :provider :openai
            :id (model-id)
            :reasoning :low
            :timeout-ms 180000}
    :instructions instructions
    :input string?
    :output string?
    :max-turns 4}))

(defn credentials? []
  (or (not (str/blank? (System/getenv "RESPONSES_API_KEY")))
      (not (str/blank? (System/getenv "OPENAI_API_KEY")))))

(defn- line! [depth text]
  (locking *out*
    (println (str (apply str (repeat depth "  ")) text))
    (flush)))

(defn trace! [{:keys [type agent tool input depth]}]
  (let [depth (or depth 0)]
    (case type
      :agent/started
      (line! depth (str "RUN    " agent))

      :tool/started
      (when (= "agent" tool)
        (locking *out*
          (println "MODEL  -> new Agent")
          (println)
          (println (:source input))
          (println)
          (flush)))

      :program/read
      (line! depth "KERNEL read")

      :program/expanded
      (line! depth "KERNEL expand")

      :program/checked
      (line! depth "KERNEL check")

      :program/evaluated
      (line! depth (str "KERNEL evaluate -> " agent))

      :tool/completed
      (when (= "agent" tool)
        (line! depth "RETURN <- generated Agent"))

      :agent/completed
      (line! depth (str "DONE   " agent))

      nil)))

(defn run-architect! [task]
  (k/run! (architect) task
          {:observe trace!
           :limits {:model-calls 4
                    :agent-forms 2
                    :depth 2
                    :parallelism 3
                    :deadline-ms 240000}}))

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

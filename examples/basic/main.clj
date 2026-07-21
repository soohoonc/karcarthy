(ns example.basic
  "Small live Responses Agent example."
  (:require [clojure.string :as str]
            [karcarthy :as k]))

(defn model-id []
  (or (System/getenv "KARCARTHY_OPENAI_MODEL") "gpt-5.6"))

(defn basic-agent []
  (k/agent
   {:name "basic-agent"
    :model {:transport :responses
            :provider :openai
            :id (model-id)
            :reasoning :low
            :timeout-ms 180000}
    :instructions "Answer clearly and concisely."
    :input-schema string?
    :output-schema string?}))

(defn credentials? []
  (or (not (str/blank? (System/getenv "RESPONSES_API_KEY")))
      (not (str/blank? (System/getenv "OPENAI_API_KEY")))))

(defn -main [& words]
  (when-not (credentials?)
    (binding [*out* *err*]
      (println "Set RESPONSES_API_KEY or OPENAI_API_KEY to run this live example."))
    (System/exit 2))
  (let [input (if (seq words)
                (str/join " " words)
                "A moon garden's leaves are turning silver. What should we check first?")
        run (k/run! (basic-agent) input)]
    (if (= :completed (:status run))
      (println (:output run))
      (do
        (binding [*out* *err*]
          (println "Run failed:" (get-in run [:error :message])))
        (System/exit 1)))))

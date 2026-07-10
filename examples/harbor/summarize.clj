(require '[clojure.data.json :as json]
         '[clojure.java.io :as io])

(def jobs-root
  (io/file (or (first *command-line-args*) "examples/harbor/jobs")))

(def candidates ["direct" "specialist"])

(defn result-files [candidate]
  (let [root (io/file jobs-root candidate)]
    (if (.exists root)
      (->> (file-seq root)
           (filter #(and (.isFile %)
                         (= "result.json" (.getName %))))
           vec)
      [])))

(defn trial-result [file]
  (let [result (json/read-str (slurp file) :key-fn keyword)]
    (when (:task_name result) result)))

(defn reward [result]
  (double (or (get-in result [:verifier_result :rewards :reward]) 0)))

(def scores
  (mapv (fn [candidate]
          (let [rewards (mapv reward (keep trial-result
                                           (result-files candidate)))]
            {:candidate candidate
             :rewards rewards
             :passed (count (filter pos? rewards))
             :total (count rewards)
             :score (if (seq rewards)
                      (/ (reduce + rewards) (count rewards))
                      0.0)}))
        candidates))

(def winner (apply max-key :score scores))
(def scoreboard {:metric "Harbor verifier mean reward"
                 :candidates scores
                 :winner (:candidate winner)
                 :score (:score winner)})

(doseq [{:keys [candidate passed total score]} scores]
  (println (format "%-12s %d/%d mean=%.3f"
                   candidate passed total score)))
(println "winner:" (:winner scoreboard))
(spit (io/file jobs-root "scoreboard.json")
      (json/write-str scoreboard :escape-slash false))

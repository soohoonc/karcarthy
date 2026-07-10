(require '[clojure.data.json :as json]
         '[clojure.java.io :as io])

(def jobs-root (io/file (or (first *command-line-args*)
                            "examples/harbor/jobs")))

(defn result-files [strategy]
  (let [root (io/file jobs-root strategy)]
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

(def strategies ["constant" "first-line" "target-parser"])

(def candidates
  (mapv (fn [strategy]
          (let [results (keep trial-result (result-files strategy))
                rewards (mapv reward results)]
            {:strategy strategy
             :rewards rewards
             :passed (count (filter pos? rewards))
             :total (count rewards)
             :score (if (seq rewards)
                      (/ (reduce + rewards) (count rewards))
                      0.0)}))
        strategies))

(def winner (apply max-key :score candidates))
(def scoreboard {:metric "Harbor verifier mean reward"
                 :candidates candidates
                 :winner (:strategy winner)
                 :score (:score winner)})

(doseq [{:keys [strategy passed total]} candidates]
  (println (format "%-14s [%s%s] %d/%d"
                   strategy
                   (apply str (repeat passed "#"))
                   (apply str (repeat (- total passed) "."))
                   passed total)))
(println "winner:" (:winner scoreboard))
(spit (io/file jobs-root "scoreboard.json")
      (json/write-str scoreboard :escape-slash false))

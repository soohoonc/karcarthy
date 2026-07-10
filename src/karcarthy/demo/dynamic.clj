(ns karcarthy.demo.dynamic
  "Offline demonstrations of runtime Agent generation and metric search."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [karcarthy :as k])
  (:import [java.nio.file Files Path Paths]
           [java.nio.file.attribute FileAttribute]))

(def strategy-order
  "Candidate order used by the deterministic hill-climbing demos."
  [:constant :first-line :target-parser])

(def evaluation-tasks
  [{:id "alpha"
    :prompt "Store the requested value in answer.txt.\nTARGET=alpha"
    :expected "alpha"}
   {:id "seoul-42"
    :prompt "Store the requested value in answer.txt.\nTARGET=seoul-42"
    :expected "seoul-42"}
   {:id "vector"
    :prompt "Store the requested value in answer.txt.\nTARGET=vector_7"
    :expected "vector_7"}])

(defn- strategy-expression [strategy]
  (case strategy
    :constant
    "\"alpha\""

    :first-line
    "(first (clojure.string/split-lines task))"

    :target-parser
    "(or (second (re-find #\"(?m)^TARGET=([A-Za-z0-9_-]+)$\" task)) \"missing\")"

    (throw (ex-info "Unknown dynamic demo strategy"
                    {:strategy strategy :known strategy-order}))))

(defn candidate-source
  "Return the complete Agent form submitted to karcarthy's built-in agent Tool."
  [strategy]
  (str "(agent {:name \"generated-" (name strategy)
       "\" :input string? :output string?}\n"
       "  [task]\n"
       "  (let [answer " (strategy-expression strategy) "\n"
       "        cwd (:cwd (context))\n"
       "        path (str cwd \"/answer.txt\")]\n"
       "    (spit path (str answer \"\\n\"))\n"
       "    answer))"))

(defn- architect-model [strategy]
  (let [source (candidate-source strategy)]
    (k/fake-model
     (fn [{:keys [messages]}]
       (if (= :tool (:role (first messages)))
         {:type :final :output (:content (first messages))}
         {:type :tool-calls
          :calls [{:id (str "create_" (name strategy))
                   :name "agent"
                   :input {:source source
                           :input (:content (first messages))}}]})))))

(defn dynamic-architect
  "Build an offline model Agent that writes and runs a new Agent at runtime."
  [strategy]
  (k/agent
   {:name (str "architect-" (name strategy))
    :description "Generate and execute one candidate Agent program."
    :model {:id "offline" :transport (architect-model strategy)}
    :instructions
    (str "Create the candidate Agent for strategy " (name strategy)
         " and run it with the complete task input.")
    :input string?
    :output string?}))

(defn run-candidate!
  "Run one strategy in `cwd` and return a compact, serializable trial record."
  [strategy {:keys [id prompt expected]} cwd]
  (let [directory (.toFile ^Path cwd)
        _ (.mkdirs directory)
        answer-file (io/file directory "answer.txt")
        _ (Files/deleteIfExists (.toPath answer-file))
        run (k/run! (dynamic-architect strategy) prompt
                    {:context {:cwd (.getAbsolutePath directory)}})
        actual (when (.isFile answer-file) (str/trim (slurp answer-file)))
        agents (->> (:events run)
                    (filter #(= :agent/started (:type %)))
                    (mapv :agent))
        program-events (->> (:events run)
                            (map :type)
                            (filter #(= "program" (namespace %)))
                            vec)]
    {:task id
     :strategy (name strategy)
     :status (name (:status run))
     :expected expected
     :actual actual
     :passed? (and (= :completed (:status run)) (= expected actual))
     :agents agents
     :program-events (mapv str program-events)
     :agent-forms (get-in run [:usage :agent-forms])
     :source (candidate-source strategy)
     :events (:events run)}))

(defn evaluate-strategy!
  "Evaluate one strategy over every demo task."
  [strategy root]
  (let [trials (mapv (fn [task]
                       (run-candidate! strategy task
                                       (.resolve ^Path root (:id task))))
                     evaluation-tasks)
        passed (count (filter :passed? trials))]
    {:strategy (name strategy)
     :passed passed
     :total (count trials)
     :score (/ passed (double (count trials)))
     :trials trials}))

(defn hill-climb!
  "Evaluate all candidate Agent forms and retain the highest-scoring one."
  [root]
  (let [root (.toAbsolutePath ^Path root)
        candidates
        (mapv (fn [strategy]
                (evaluate-strategy! strategy
                                    (.resolve root (name strategy))))
              strategy-order)
        winner (apply max-key :score candidates)]
    {:metric "exact answer.txt match"
     :candidates candidates
     :winner (:strategy winner)
     :score (:score winner)}))

(def ^:private visible-event-types
  #{:run/started :run/completed
    :agent/started :agent/completed
    :tool/started :tool/completed
    :program/read :program/expanded :program/checked :program/evaluated})

(defn- event-line [{:keys [type depth agent tool]}]
  (let [indent (apply str (repeat (max 0 (inc (or depth -1))) "  "))
        detail (case type
                 :agent/started agent
                 :agent/completed agent
                 :tool/started tool
                 :tool/completed tool
                 :program/evaluated agent
                 nil)]
    (str indent (subs (str type) 1) (when detail (str "  " detail)))))

(defn print-trace!
  "Print the generated-program and Agent lineage from a compact trial."
  [trial]
  (doseq [event (:events trial)
          :when (contains? visible-event-types (:type event))]
    (println (event-line event))))

(defn- printable-trial [trial]
  (dissoc trial :events))

(defn print-scoreboard! [{:keys [candidates winner metric]}]
  (println "metric:" metric)
  (doseq [{:keys [strategy passed total]} candidates]
    (println (format "%-14s [%s%s] %d/%d"
                     strategy
                     (apply str (repeat passed "#"))
                     (apply str (repeat (- total passed) "."))
                     passed total)))
  (println "winner:" winner))

(defn run-dynamic-demo!
  ([]
   (run-dynamic-demo! "Store the requested value in answer.txt.\nTARGET=seoul-42"))
  ([prompt]
   (let [cwd (-> (Paths/get "target/dynamic-agent-demo" (make-array String 0))
                 .toAbsolutePath)
         trial (run-candidate! :target-parser
                               {:id "visual-proof"
                                :prompt prompt
                                :expected "seoul-42"}
                               cwd)]
     (println "runtime Agent trace")
     (print-trace! trial)
     (println "output:" (:actual trial))
     (println "artifact:" (str (.resolve cwd "answer.txt")))
     (println "submitted source:")
     (println (:source trial))
     trial)))

(defn run-hill-climb-demo! []
  (let [root (-> (Paths/get "target/hill-climb-results"
                            (make-array String 0))
                 .toAbsolutePath)
        result (hill-climb! root)
        output (.resolve root "results.json")
        serializable (update result :candidates
                             (fn [candidates]
                               (mapv #(update % :trials
                                              (fn [trials]
                                                (mapv printable-trial trials)))
                                     candidates)))]
    (Files/createDirectories root (make-array FileAttribute 0))
    (spit (.toFile output) (json/write-str serializable :escape-slash false))
    (print-scoreboard! result)
    (println "results:" (str output))
    result))

(defn harbor-agent
  "ACP Agent factory used by the Harbor example distribution."
  [_session-context]
  (let [strategy (keyword (or (System/getenv "KARCARTHY_STRATEGY")
                              "target-parser"))]
    (dynamic-architect strategy)))

(defn -main [& [command]]
  (case command
    "hill-climb" (run-hill-climb-demo!)
    (run-dynamic-demo!)))

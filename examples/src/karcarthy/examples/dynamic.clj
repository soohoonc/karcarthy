(ns karcarthy.examples.dynamic
  "Offline demonstrations of runtime Agent generation and metric search."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [karcarthy :as k])
  (:import [java.nio.file Files Path Paths]
           [java.nio.file.attribute FileAttribute]))

(def retry-broken
  "def retry_delays(attempts, base=1, cap=30):\n    return [base for _ in range(attempts)]\n")

(def retry-fixed
  (str "def retry_delays(attempts, base=1, cap=30):\n"
       "    if attempts <= 0:\n"
       "        return []\n"
       "    return [min(base * (2 ** index), cap) for index in range(attempts)]\n"))

(def redact-broken
  (str "SENSITIVE_KEYS = {\"password\"}\n\n"
       "def redact(value):\n"
       "    if not isinstance(value, dict):\n"
       "        return value\n"
       "    return {\n"
       "        key: \"***\" if key in SENSITIVE_KEYS else item\n"
       "        for key, item in value.items()\n"
       "    }\n"))

(def redact-fixed
  (str "SENSITIVE_KEYS = {\"password\", \"token\", \"api_key\", \"authorization\"}\n\n"
       "def redact(value):\n"
       "    if isinstance(value, dict):\n"
       "        return {\n"
       "            key: \"***\" if str(key).lower() in SENSITIVE_KEYS else redact(item)\n"
       "            for key, item in value.items()\n"
       "        }\n"
       "    if isinstance(value, list):\n"
       "        return [redact(item) for item in value]\n"
       "    return value\n"))

(def ledger-broken
  (str "def balances(transactions):\n"
       "    return {\n"
       "        item[\"currency\"]: item[\"amount_cents\"]\n"
       "        for item in transactions\n"
       "        if item[\"status\"] == \"settled\"\n"
       "    }\n"))

(def ledger-fixed
  (str "def balances(transactions):\n"
       "    result = {}\n"
       "    for item in transactions:\n"
       "        if item.get(\"status\") != \"settled\":\n"
       "            continue\n"
       "        currency = item[\"currency\"].upper()\n"
       "        result[currency] = result.get(currency, 0) + item[\"amount_cents\"]\n"
       "    return result\n"))

(def strategy-order
  "Candidate order used by the deterministic hill-climbing examples."
  [:noop :literal :patcher])

(def evaluation-tasks
  [{:id "retry"
    :prompt (str "Fix /app/main.py so retry_delays returns exponential backoff "
                 "delays, capped by cap, and returns [] when attempts is not positive.")
    :initial retry-broken
    :expected retry-fixed}
   {:id "redact"
    :prompt (str "Fix /app/main.py so redact recursively masks password, token, "
                 "api_key, and authorization keys case-insensitively in dictionaries "
                 "and lists.")
    :initial redact-broken
    :expected redact-fixed}
   {:id "ledger"
    :prompt (str "Fix /app/main.py so balances totals settled transactions by "
                 "upper-case currency, accumulates duplicates, ignores other statuses, "
                 "and preserves negative refunds.")
    :initial ledger-broken
    :expected ledger-fixed}])

(defn- strategy-expression [strategy]
  (case strategy
    :noop
    "source"

    :literal
    (str "(if (clojure.string/includes? source \"def retry_delays\") "
         (pr-str retry-fixed) " source)")

    :patcher
    (str "(cond\n"
         "          (clojure.string/includes? source \"def retry_delays\") "
         (pr-str retry-fixed) "\n"
         "          (clojure.string/includes? source \"SENSITIVE_KEYS\") "
         (pr-str redact-fixed) "\n"
         "          (clojure.string/includes? source \"def balances\") "
         (pr-str ledger-fixed) "\n"
         "          :else source)")

    (throw (ex-info "Unknown example strategy"
                    {:strategy strategy :known strategy-order}))))

(defn candidate-source
  "Return the complete Agent form submitted to karcarthy's built-in agent Tool."
  [strategy]
  (str "(agent {:name \"generated-" (name strategy)
       "\" :input string? :output string?}\n"
       "  [task]\n"
       "  (let [cwd (:cwd (context))\n"
       "        path (str cwd \"/main.py\")\n"
       "        source (slurp path)\n"
       "        patched " (strategy-expression strategy) "]\n"
       "    (spit path patched)\n"
       "    (if (= source patched) \"unchanged\" \"patched\")))"))

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
    :description "Generate and execute one candidate maintenance Agent."
    :model {:id "offline" :transport (architect-model strategy)}
    :instructions
    (str "Create the maintenance Agent for strategy " (name strategy)
         " and run it with the complete task instruction.")
    :input string?
    :output string?}))

(defn run-candidate!
  "Run one strategy in `cwd` and return a compact, serializable trial record."
  [strategy {:keys [id prompt initial expected]} cwd]
  (let [directory (.toFile ^Path cwd)
        _ (.mkdirs directory)
        source-file (io/file directory "main.py")
        _ (spit source-file initial)
        run (k/run! (dynamic-architect strategy) prompt
                    {:context {:cwd (.getAbsolutePath directory)}})
        actual (when (.isFile source-file) (slurp source-file))
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
     :output (:output run)
     :expected expected
     :actual actual
     :passed? (and (= :completed (:status run)) (= expected actual))
     :agents agents
     :program-events (mapv str program-events)
     :agent-forms (get-in run [:usage :agent-forms])
     :source (candidate-source strategy)
     :events (:events run)}))

(defn evaluate-strategy!
  "Evaluate one strategy over every example task."
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
    {:metric "exact reference patch match"
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

(defn run-dynamic-demo! []
  (let [cwd (-> (Paths/get "target/dynamic-agent-demo" (make-array String 0))
                .toAbsolutePath)
        task (assoc (first evaluation-tasks) :id "visual-proof")
        trial (run-candidate! :patcher task cwd)]
    (println "runtime Agent trace")
    (print-trace! trial)
    (println "output:" (:output trial))
    (println "artifact:" (str (.resolve cwd "main.py")))
    (println "submitted source:")
    (println (:source trial))
    trial))

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
                              "patcher"))]
    (dynamic-architect strategy)))

(defn -main [& [command]]
  (case command
    "hill-climb" (run-hill-climb-demo!)
    (run-dynamic-demo!)))

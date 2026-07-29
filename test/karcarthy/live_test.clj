(load-file "examples/coding/main.clj")

(ns karcarthy.live-test
  "Paid verification of the public examples and core Agent features."
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :as t :refer [deftest is]]
            [karcarthy :as k]
            [example.coding :as coding])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files Path]
           [java.nio.file.attribute FileAttribute]))

(defn- live? []
  (= "1" (System/getenv "KARCARTHY_LIVE")))

(defn- credentials? []
  (or (not (str/blank? (System/getenv "RESPONSES_API_KEY")))
      (not (str/blank? (System/getenv "OPENAI_API_KEY")))))

(defn- model-id []
  (or (System/getenv "KARCARTHY_OPENAI_MODEL") "gpt-5.6"))

(defn- live-model []
  {:transport :responses
   :provider :openai
   :id (model-id)
   :reasoning :low
   :timeout-ms 180000})

(k/deftool static-stamp
  {:description "Return a deterministic marker for this live Tool test."
   :input-schema {:type "object"
                  :properties {"text" {:type "string"}}
                  :required ["text"]
                  :additionalProperties false}
   :output-schema string?}
  [{:keys [text]}]
  (str "static-tool:" text))

(defn- predefined-support-router []
  (let [billing-agent
        (k/agent
         {:name "billing-agent"
          :description
          "Handle duplicate charges, refunds, invoices, and payment questions."
          :model (live-model)
          :instructions
          "Return exactly BILLING-AGENT-HANDLED."
          :input-schema string?
          :output-schema string?
          :max-turns 2})
        incident-responder
        (k/agent
         {:name "incident-responder"
          :description
          "Handle errors, outages, broken integrations, and product failures."
          :model (live-model)
          :instructions
          "Return exactly INCIDENT-RESPONDER-HANDLED."
          :input-schema string?
          :output-schema string?
          :max-turns 2})]
    (k/agent
     {:name "predefined-support-router"
      :model (live-model)
      :instructions
      (str
       "Resolve each support request by delegating it to exactly one available "
       "agent whose description matches the issue. Return that agent's answer "
       "exactly.")
      :agents [billing-agent incident-responder]
      :input-schema string?
      :output-schema string?
      :max-turns 4})))

(defn- predefined-review-board []
  (let [availability-reviewer
        (k/agent
         {:name "availability-reviewer"
          :description
          "Assess reliability, migration, rollback, and operational continuity."
          :model (live-model)
          :instructions
          "Return exactly AVAILABILITY-REVIEW-COMPLETE."
          :input-schema string?
          :output-schema string?
          :max-turns 2})
        security-reviewer
        (k/agent
         {:name "security-reviewer"
          :description
          "Assess access control, sensitive data, secrets, and trust boundaries."
          :model (live-model)
          :instructions
          "Return exactly SECURITY-REVIEW-COMPLETE."
          :input-schema string?
          :output-schema string?
          :max-turns 2})]
    (k/agent
     {:name "predefined-review-board"
      :model (live-model)
      :instructions
      (str
       "Evaluate every infrastructure change by consulting the availability "
       "reviewer and security reviewer. Return a concise recommendation based "
       "on both reviews.")
      :agents [availability-reviewer security-reviewer]
      :input-schema string?
      :output-schema string?
      :max-turns 5})))

(defn- feature-matrix-agent []
  (let [static-agent
        (k/agent
         {:name "static-agent"
          :description "Return the fixed marker requested by this live test."
          :model (live-model)
          :instructions "Return exactly static-agent-ok. Do not call a Tool."
          :input-schema string?
          :output-schema string?
          :max-turns 2})
        dynamic-code
        (str
         "(let [stamp (tool {:name \"dynamic-stamp\" "
         ":description \"Return a deterministic dynamic Tool marker.\" "
         ":input-schema {:type \"object\" "
         ":properties {\"text\" {:type \"string\"}} "
         ":required [\"text\"] :additionalProperties false} "
         ":output-schema string?} "
         "[value] (str \"dynamic-tool:\" (:text value))) "
         "worker (agent {:name \"dynamic-worker\" "
         ":description \"Exercise a Tool created inside eval.\" "
         ":model " (pr-str (live-model)) " "
         ":instructions \"Call dynamic-stamp exactly once with text dynamic-agent. "
         "Return the Tool result exactly. Do not call eval.\" "
         ":tools [stamp] :input-schema string? :output-schema string? :max-turns 3})] "
         "(:output (run! worker input)))")]
    (k/agent
     {:name "live-feature-matrix"
      :model (live-model)
      :instructions
      (str
       "Exercise every requested harness feature before answering. "
       "First call static-stamp with text static. "
       "Then call static-agent with input static. "
       "Then call eval exactly once with this exact code: "
       dynamic-code " "
       "Only after all three calls succeed, return exactly LIVE-FEATURES-OK.")
      :tools [static-stamp k/eval]
      :agents [static-agent]
      :input-schema string?
      :output-schema string?
      :max-turns 8})))

(defn- routing-prompt-agent []
  (k/agent
   {:name "support-router"
    :model (live-model)
    :instructions
    (str
     "Resolve customer support requests. Delegate each request to exactly one "
     "newly configured agent whose role matches the issue. Return that agent's "
     "answer without adding unsupported facts.")
    :tools [k/eval]
    :input-schema string?
    :output-schema string?
    :max-turns 4}))

(defn- parallel-prompt-agent []
  (k/agent
   {:name "comparison-coordinator"
    :model (live-model)
    :instructions
    (str
     "Assess engineering proposals by obtaining one independent analysis of the "
     "benefits and another independent analysis of the risks. Perform independent "
     "work concurrently when possible. Return a concise response with Benefits, "
     "Risks, and Recommendation sections.")
    :tools [k/eval]
    :input-schema string?
    :output-schema string?
    :max-turns 4}))

(defn- authored-tool-prompt-agent []
  (k/agent
   {:name "capability-author"
    :model (live-model)
    :instructions
    (str
     "Analyze text by delegating the work to a focused analyst. When an answer "
     "depends on exact computation, give the analyst a task-specific "
     "deterministic tool instead of asking it to estimate. Return the calculated "
     "result and a concise conclusion.")
    :tools [k/eval]
    :input-schema string?
    :output-schema string?
    :max-turns 4}))

(defn- selective-eval-agent []
  (k/agent
   {:name "selective-eval"
    :model (live-model)
    :instructions
    "Resolve the user's request using the available capabilities as appropriate."
    :tools [k/eval]
    :input-schema string?
    :output-schema string?
    :max-turns 3}))

(defn- temp-directory []
  (Files/createTempDirectory "karcarthy-live-coding-"
                             (make-array FileAttribute 0)))

(defn- delete-tree! [^Path root]
  (doseq [file (reverse (file-seq (.toFile root)))]
    (Files/deleteIfExists (.toPath file))))

(defn- write! [^Path root name content]
  (Files/writeString (.resolve root name) content StandardCharsets/UTF_8
                     (make-array java.nio.file.OpenOption 0)))

(deftest model-selects-one-predefined-agent
  (is (live?) "Set KARCARTHY_LIVE=1 to authorize the paid live test.")
  (is (credentials?) "Set RESPONSES_API_KEY or OPENAI_API_KEY.")
  (when (and (live?) (credentials?))
    (let [run (k/run! (predefined-support-router)
                      "I was charged twice for invoice INV-42."
                      {:limits {:model-calls 4
                                :depth 1
                                :concurrency 4
                                :deadline-ms 300000}})
          agent-names (->> (:events run)
                           (filter #(= :agent/started (:type %)))
                           (mapv :agent))
          tool-names (->> (:events run)
                          (filter #(= :tool/started (:type %)))
                          (mapv :tool))]
      (is (= :completed (:status run)) (pr-str (:error run)))
      (is (= "BILLING-AGENT-HANDLED" (some-> (:output run) str/trim)))
      (is (= ["predefined-support-router" "billing-agent"] agent-names)
          (pr-str agent-names))
      (is (= ["billing-agent"] tool-names) (pr-str tool-names))
      (is (zero? (get-in run [:usage :evals]))))))

(deftest model-consults-multiple-predefined-agents
  (is (live?) "Set KARCARTHY_LIVE=1 to authorize the paid live test.")
  (is (credentials?) "Set RESPONSES_API_KEY or OPENAI_API_KEY.")
  (when (and (live?) (credentials?))
    (let [run (k/run! (predefined-review-board)
                      (str "Move the customer database to a new distributed SQL "
                           "service before the holiday traffic peak.")
                      {:limits {:model-calls 8
                                :depth 1
                                :concurrency 4
                                :deadline-ms 300000}})
          completed-outputs (->> (:events run)
                                 (filter #(= :agent/completed (:type %)))
                                 (map (juxt :agent :output))
                                 (into {}))
          agent-names (->> (:events run)
                           (filter #(= :agent/started (:type %)))
                           (map :agent)
                           set)
          tool-names (->> (:events run)
                          (filter #(= :tool/started (:type %)))
                          (map :tool)
                          set)]
      (is (= :completed (:status run)) (pr-str (:error run)))
      (is (= "AVAILABILITY-REVIEW-COMPLETE"
             (get completed-outputs "availability-reviewer"))
          (pr-str completed-outputs))
      (is (= "SECURITY-REVIEW-COMPLETE"
             (get completed-outputs "security-reviewer"))
          (pr-str completed-outputs))
      (is (= #{"predefined-review-board"
               "availability-reviewer"
               "security-reviewer"}
             agent-names)
          (pr-str agent-names))
      (is (= #{"availability-reviewer" "security-reviewer"} tool-names)
          (pr-str tool-names))
      (is (zero? (get-in run [:usage :evals]))))))

(deftest static-tools-static-agents-and-dynamic-eval-work-together
  (is (live?) "Set KARCARTHY_LIVE=1 to authorize the paid live test.")
  (is (credentials?) "Set RESPONSES_API_KEY or OPENAI_API_KEY.")
  (when (and (live?) (credentials?))
    (let [run (k/run! (feature-matrix-agent) "Run the live feature matrix."
                      {:limits {:model-calls 12
                                :evals 1
                                :depth 2
                                :concurrency 8
                                :deadline-ms 300000}})
          tool-names (->> (:events run)
                          (filter #(= :tool/started (:type %)))
                          (map :tool)
                          set)
          agent-names (->> (:events run)
                           (filter #(= :agent/started (:type %)))
                           (map :agent)
                           set)
          eval-types (->> (:events run)
                          (map :type)
                          (filter #(= "eval" (namespace %)))
                          set)]
      (is (= :completed (:status run)) (pr-str (:error run)))
      (is (= "LIVE-FEATURES-OK" (some-> (:output run) str/trim)))
      (is (every? tool-names
                  ["static-stamp" "static-agent" "eval" "dynamic-stamp"])
          (pr-str tool-names))
      (is (every? agent-names
                  ["live-feature-matrix" "static-agent"
                   "dynamic-worker"])
          (pr-str agent-names))
      (is (= #{:eval/started :eval/expanded :eval/completed} eval-types)
          (pr-str eval-types))
      (is (= 1 (get-in run [:usage :evals]))))))

(deftest eval-description-teaches-input-based-agent-selection
  (is (live?) "Set KARCARTHY_LIVE=1 to authorize the paid live test.")
  (is (credentials?) "Set RESPONSES_API_KEY or OPENAI_API_KEY.")
  (when (and (live?) (credentials?))
    (let [run (k/run! (routing-prompt-agent)
                      (str "I was charged twice for invoice INV-42. Identify "
                           "which support role should handle this and what "
                           "information they need next.")
                      {:limits {:model-calls 4
                                :evals 1
                                :depth 1
                                :concurrency 4
                                :deadline-ms 300000}})
          eval-code (->> (:events run)
                         (filter #(= :eval/started (:type %)))
                         first
                         :code)
          agent-names (->> (:events run)
                           (filter #(= :agent/started (:type %)))
                           (mapv :agent))]
      (is (= :completed (:status run)) (pr-str (:error run)))
      (is (not (str/blank? (:output run))) (pr-str (:output run)))
      (is (= 1 (get-in run [:usage :evals])))
      (is (and (string? eval-code)
               (str/includes? eval-code "(agent")
               (str/includes? eval-code ":instructions")
               (str/includes? eval-code "run!"))
          (pr-str eval-code))
      (is (= 2 (count agent-names))
          (pr-str agent-names)))))

(deftest eval-description-keeps-trivial-work-direct
  (is (live?) "Set KARCARTHY_LIVE=1 to authorize the paid live test.")
  (is (credentials?) "Set RESPONSES_API_KEY or OPENAI_API_KEY.")
  (when (and (live?) (credentials?))
    (let [run (k/run! (selective-eval-agent)
                      "Return exactly DIRECT-PATH."
                      {:limits {:model-calls 2
                                :evals 1
                                :depth 1
                                :concurrency 2
                                :deadline-ms 300000}})]
      (is (= :completed (:status run)) (pr-str (:error run)))
      (is (= "DIRECT-PATH" (some-> (:output run) str/trim)))
      (is (zero? (get-in run [:usage :evals])) (pr-str (:events run))))))

(deftest eval-description-teaches-parallel-agent-calls
  (is (live?) "Set KARCARTHY_LIVE=1 to authorize the paid live test.")
  (is (credentials?) "Set RESPONSES_API_KEY or OPENAI_API_KEY.")
  (when (and (live?) (credentials?))
    (let [run (k/run! (parallel-prompt-agent)
                      (str "Assess moving the primary customer database from "
                           "PostgreSQL to a new distributed SQL service before "
                           "the holiday traffic peak.")
                      {:limits {:model-calls 5
                                :evals 1
                                :depth 1
                                :concurrency 6
                                :deadline-ms 300000}})
          eval-code (->> (:events run)
                         (filter #(= :eval/started (:type %)))
                         first
                         :code)
          agent-names (->> (:events run)
                           (filter #(= :agent/started (:type %)))
                           (map :agent)
                           set)]
      (is (= :completed (:status run)) (pr-str (:error run)))
      (is (str/includes? (str/lower-case (or (:output run) "")) "benefit")
          (pr-str (:output run)))
      (is (str/includes? (str/lower-case (or (:output run) "")) "risk")
          (pr-str (:output run)))
      (is (= 1 (get-in run [:usage :evals])))
      (is (and (string? eval-code) (str/includes? eval-code "future"))
          (pr-str eval-code))
      (is (and (string? eval-code) (str/includes? eval-code "deref"))
          (pr-str eval-code))
      (is (= 3 (count agent-names))
          (pr-str agent-names)))))

(deftest eval-description-teaches-tool-and-agent-authoring
  (is (live?) "Set KARCARTHY_LIVE=1 to authorize the paid live test.")
  (is (credentials?) "Set RESPONSES_API_KEY or OPENAI_API_KEY.")
  (when (and (live?) (credentials?))
    (let [run (k/run! (authored-tool-prompt-agent)
                      (str "Count the whitespace-separated words in each phrase "
                           "and say which is longer: \"red green blue\" and "
                           "\"one two three four\".")
                      {:limits {:model-calls 5
                                :evals 1
                                :depth 1
                                :concurrency 4
                                :deadline-ms 300000}})
          eval-code (->> (:events run)
                         (filter #(= :eval/started (:type %)))
                         first
                         :code)
          agent-names (->> (:events run)
                           (filter #(= :agent/started (:type %)))
                           (mapv :agent))
          tool-names (->> (:events run)
                          (filter #(= :tool/started (:type %)))
                          (mapv :tool))]
      (is (= :completed (:status run)) (pr-str (:error run)))
      (is (str/includes? (or (:output run) "") "3") (pr-str (:output run)))
      (is (str/includes? (or (:output run) "") "4") (pr-str (:output run)))
      (is (= 1 (get-in run [:usage :evals])))
      (is (and (string? eval-code)
               (str/includes? eval-code "(tool")
               (str/includes? eval-code "(agent"))
          (pr-str eval-code))
      (is (= 2 (count agent-names))
          (pr-str agent-names))
      (is (some #(not= "eval" %) tool-names)
          (pr-str tool-names)))))

(deftest coding-example-inspects-edits-and-verifies
  (is (live?) "Set KARCARTHY_LIVE=1 to authorize the paid live test.")
  (is (credentials?) "Set RESPONSES_API_KEY or OPENAI_API_KEY.")
  (when (and (live?) (credentials?))
    (let [root (temp-directory)]
      (try
        (write! root "README.md"
                "# Scheduler\n\nRun `python3 -m unittest -v` before submitting changes.\n")
        (write! root "scheduler.py"
                (str "def due_jobs(jobs, now):\n"
                     "    return [job for job in jobs if job['run_at'] <= now]\n"))
        (write! root "test_scheduler.py"
                (str "import unittest\n\n"
                     "from scheduler import due_jobs\n\n"
                     "class SchedulerTest(unittest.TestCase):\n"
                     "    def test_only_pending_due_jobs_in_priority_order(self):\n"
                     "        jobs = [\n"
                     "            {'id': 'late', 'run_at': 20, 'state': 'pending', 'priority': 1},\n"
                     "            {'id': 'done', 'run_at': 5, 'state': 'completed', 'priority': 9},\n"
                     "            {'id': 'high', 'run_at': 10, 'state': 'pending', 'priority': 5},\n"
                     "            {'id': 'low', 'run_at': 10, 'state': 'pending', 'priority': 1},\n"
                     "        ]\n"
                     "        self.assertEqual(\n"
                     "            ['high', 'low'],\n"
                     "            [job['id'] for job in due_jobs(jobs, 10)],\n"
                     "        )\n\n"
                     "if __name__ == '__main__':\n"
                     "    unittest.main()\n"))
        (let [session (coding/coding-session
                       {:cwd (str root)
                        :approval :never
                        :model (live-model)})
              run (coding/ask!
                   session
                   (str "A scheduler deployment is returning completed jobs and "
                        "processing equal-time jobs in the wrong order. Investigate "
                        "the repository, fix the implementation, and verify it."))
              check (shell/sh "python3" "-m" "unittest" "-v"
                              :dir (str root))
              used-tools (->> (:events run)
                              (filter #(= :tool/started (:type %)))
                              (map :tool)
                              set)]
          (is (= :completed (:status run)) (pr-str (:error run)))
          (is (zero? (:exit check)) (:err check))
          (is (contains? used-tools "bash") (pr-str used-tools)))
        (finally
          (delete-tree! root))))))

(defn -main [& _]
  (when-not (live?)
    (println "Set KARCARTHY_LIVE=1 to authorize the paid live tests.")
    (System/exit 2))
  (when-not (credentials?)
    (println "Set RESPONSES_API_KEY or OPENAI_API_KEY; do not put it in source.")
    (System/exit 2))
  (let [{:keys [fail error]} (t/run-tests 'karcarthy.live-test)]
    (shutdown-agents)
    (System/exit (if (pos? (+ (or fail 0) (or error 0))) 1 0))))

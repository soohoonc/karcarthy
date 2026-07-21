(load-file "examples/basic/main.clj")
(load-file "examples/architect/main.clj")
(load-file "examples/coding/main.clj")

(ns karcarthy.live-test
  "Paid verification of the public examples and core Agent features."
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :as t :refer [deftest is]]
            [karcarthy :as k]
            [example.architect :as architect]
            [example.basic :as basic]
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

(defn- feature-matrix-agent []
  (let [static-specialist
        (k/agent
         {:name "static-specialist"
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
         "specialist (agent {:name \"dynamic-specialist\" "
         ":description \"Exercise a Tool created inside eval.\" "
         ":model " (pr-str (live-model)) " "
         ":instructions \"Call dynamic-stamp exactly once with text dynamic-agent. "
         "Return the Tool result exactly. Do not call eval.\" "
         ":tools [stamp] :input-schema string? :output-schema string? :max-turns 3})] "
         "(:output (run! specialist input)))")]
    (k/agent
     {:name "live-feature-matrix"
      :model (live-model)
      :instructions
      (str
       "Exercise every requested harness feature before answering. "
       "First call static-stamp with text static. "
       "Then call static-specialist with input static. "
       "Then call eval exactly once with this exact code: "
       dynamic-code " "
       "Only after all three calls succeed, return exactly LIVE-FEATURES-OK.")
      :tools [static-stamp]
      :agents [static-specialist]
      :input-schema string?
      :output-schema string?
      :max-turns 8})))

(defn- temp-directory []
  (Files/createTempDirectory "karcarthy-live-coding-"
                             (make-array FileAttribute 0)))

(defn- delete-tree! [^Path root]
  (doseq [file (reverse (file-seq (.toFile root)))]
    (Files/deleteIfExists (.toPath file))))

(defn- write! [^Path root name content]
  (Files/writeString (.resolve root name) content StandardCharsets/UTF_8
                     (make-array java.nio.file.OpenOption 0)))

(deftest basic-example-uses-the-live-transport
  (is (live?) "Set KARCARTHY_LIVE=1 to authorize the paid live test.")
  (is (credentials?) "Set RESPONSES_API_KEY or OPENAI_API_KEY.")
  (when (and (live?) (credentials?))
    (let [run (k/run! (basic/basic-agent) "Reply with only the word ready.")]
      (is (= :completed (:status run)) (pr-str (:error run)))
      (is (= "ready" (some-> (:output run) str/trim str/lower-case))))))

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
                  ["static-stamp" "static-specialist" "eval" "dynamic-stamp"])
          (pr-str tool-names))
      (is (every? agent-names
                  ["live-feature-matrix" "static-specialist"
                   "dynamic-specialist"])
          (pr-str agent-names))
      (is (= #{:eval/started :eval/expanded :eval/completed} eval-types)
          (pr-str eval-types))
      (is (= 1 (get-in run [:usage :evals]))))))

(deftest architect-example-chooses-and-runs-specialists
  (is (live?) "Set KARCARTHY_LIVE=1 to authorize the paid live test.")
  (is (credentials?) "Set RESPONSES_API_KEY or OPENAI_API_KEY.")
  (when (and (live?) (credentials?))
    (let [run (architect/run-architect!
               architect/default-task
               (k/monitor))
          eval-events (->> (:events run)
                           (map :type)
                           (filter #(= "eval" (namespace %)))
                           frequencies)
          agent-events (->> (:events run)
                            (filter #(= :agent/started (:type %)))
                            vec)
          root-events (filter #(= "architect" (:agent %)) agent-events)
          specialist-events (remove #(= "architect" (:agent %)) agent-events)]
      (is (= :completed (:status run)) (pr-str (:error run)))
      (is (= 1 (get-in run [:usage :evals])))
      (is (= {:eval/started 1 :eval/expanded 1 :eval/completed 1}
             eval-events))
      (is (= 1 (count root-events)))
      (is (<= 2 (count specialist-events) 3))
      (is (= (count specialist-events)
             (count (set (map :agent specialist-events)))))
      (is (every? #(= 1 (:depth %)) specialist-events)))))

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
        (let [run (coding/run-coding!
                   (str root)
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

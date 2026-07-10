(load-file "examples/basic/main.clj")
(load-file "examples/architect/main.clj")
(load-file "examples/coding/main.clj")

(ns karcarthy.live-test
  "Paid verification of the public live examples."
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

(deftest architect-example-writes-and-runs-two-agents
  (is (live?) "Set KARCARTHY_LIVE=1 to authorize the paid live test.")
  (is (credentials?) "Set RESPONSES_API_KEY or OPENAI_API_KEY.")
  (when (and (live?) (credentials?))
    (let [run (architect/run-architect!
               "Review a migration from synchronous writes to a queue."
               (k/run-monitor))
          program-events (->> (:events run)
                              (map :type)
                              (filter #(= "program" (namespace %)))
                              set)]
      (is (= :completed (:status run)) (pr-str (:error run)))
      (is (= 2 (get-in run [:usage :agent-forms])))
      (is (= #{:program/read :program/expanded :program/checked
               :program/evaluated}
             program-events)))))

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

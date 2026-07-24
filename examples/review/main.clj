(ns example.review
  "Live trace of a code-review orchestrator creating reviewers after reading a change."
  (:require [clojure.string :as str]
            [karcarthy :as k]))

(defn model-id []
  (or (System/getenv "KARCARTHY_OPENAI_MODEL") "gpt-5.6"))

(defn model-config []
  {:transport :responses
   :provider :openai
   :id (model-id)
   :reasoning :low
   :timeout-ms 180000})

(defn instructions []
  (str
   "Act as the orchestrator for a code review. Read the proposed change before "
   "deciding what expertise the review needs. Call eval exactly once. In one "
   "ordinary Clojure expression, create two or three reviewer Agents with unique, "
   "short kebab-case names ending in -reviewer. Each name must state one concrete "
   "concern in familiar terms; avoid abstract labels such as semantics or invariants. "
   "Choose the concerns from this change; do not use a fixed catalog of roles. "
   "Configure every reviewer with "
   ":model " (pr-str (model-config)) ", :input-schema string?, and "
   ":output-schema string?. Give every reviewer the complete input. Require each "
   "one to report only concrete defects introduced by the change, with severity, "
   "file and line, a failure scenario, and a minimal fix; no style advice. "
   "Choose the reviewer responsible for the riskiest behavioral claim and instruct "
   "it to include :tools [eval] and call eval exactly once: it must create and run one Agent named "
   "finding-verifier to challenge its strongest candidate finding, then include the "
   "verdict in its report. Tell the other reviewers not to use eval. Run all top-level "
   "reviewers exactly once and concurrently with future. Dereference their Run maps "
   "and return their names, statuses, and outputs as model-safe data. After eval "
   "returns, remove duplicates and write the final review as Markdown with `## "
   "Findings` followed by findings ordered from P0 to P2, then `## Summary`. Every "
   "finding must include the verifier verdict when one exists. If there are no "
   "defects, say so explicitly. Do not call a reviewer again."))

(defn code-reviewer []
  (k/agent
   {:name "code-reviewer"
    :model (model-config)
    :instructions (instructions)
    :tools [k/eval]
    :input-schema string?
    :output-schema string?
    :max-turns 5}))

(defn credentials? []
  (or (not (str/blank? (System/getenv "RESPONSES_API_KEY")))
      (not (str/blank? (System/getenv "OPENAI_API_KEY")))))

(defn run-review!
  ([change]
   (run-review! change (k/monitor {:display :tree})))
  ([change monitor]
   (k/run! (code-reviewer) change
           {:on-event monitor
            :limits {:model-calls 12
                     :evals 2
                     :depth 2
                     :concurrency 8
                     :deadline-ms 300000}})))

(def default-change
  (str
   "Review target: PR #42 — claim due jobs in priority order\n\n"
   "Contract:\n"
   "- Only pending jobs may be claimed.\n"
   "- Return at most `limit` jobs, highest priority first.\n"
   "- Several workers may call this function concurrently; a job must be claimed once.\n\n"
   "Storage behavior:\n"
   "- `find` returns matching jobs in insertion order.\n"
   "- `save` updates one job without locking the preceding read.\n\n"
   "```diff\n"
   "diff --git a/scheduler.py b/scheduler.py\n"
   "index 2ad1c8b..7bc214e 100644\n"
   "--- a/scheduler.py\n"
   "+++ b/scheduler.py\n"
   "@@ -8,3 +8,9 @@ def enqueue(store, job):\n"
   "     store.save(job)\n"
   "+\n"
   "+def claim_due_jobs(store, now, limit=100):\n"
   "+    jobs = store.find(run_at_lte=now)[:limit]\n"
   "+    jobs.sort(key=lambda job: (-job.priority, job.run_at))\n"
   "+    for job in jobs:\n"
   "+        job.state = \"running\"\n"
   "+        store.save(job)\n"
   "+    return jobs\n"
   "```"))

(defn -main [& words]
  (when-not (credentials?)
    (binding [*out* *err*]
      (println "Set RESPONSES_API_KEY or OPENAI_API_KEY to run this live example."))
    (System/exit 2))
  (let [change (if (seq words) (str/join " " words) default-change)
        run (run-review! change)]
    (println)
    (if (= :completed (:status run))
      (do
        (println "REVIEW")
        (println (:output run)))
      (do
        (binding [*out* *err*]
          (println "Run failed:" (get-in run [:error :message])))
        (System/exit 1)))))

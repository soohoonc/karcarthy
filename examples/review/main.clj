(ns example.review
  "Live trace of a reviewer creating a specialist and verifier."
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
   "Act as the reviewer and final editor for a code review. Read the proposed "
   "change before choosing a specialist. Call eval exactly once. In one ordinary "
   "Clojure expression:\n"
   "1. Create exactly one specialist justified by the affected risk surface. "
   "When the change touches authentication, authorization, untrusted input, "
   "secrets, or sensitive data, name it security-reviewer and instruct it to "
   "review those boundaries. The bundled customer-export change meets that "
   "condition. Configure the specialist with :model "
   (pr-str (model-config))
   ". Give it the complete input and require only candidate defects introduced "
   "by the patch, with severity, file and line, a concrete failure or exploit "
   "scenario, evidence, and a minimal fix. Run it exactly once.\n"
   "2. Create one agent named verifier with the same model. Pass it the complete "
   "input plus the specialist output. Instruct it to independently try to "
   "disprove every candidate against the supplied requirements and code and return "
   "CONFIRMED, REJECTED, or NEEDS CONTEXT with exact evidence. Run it exactly once.\n"
   "3. Return both outputs as model-safe data.\n"
   "After eval returns, write the final review as Markdown with `## Findings` "
   "followed by verified findings ordered from P0 to P2, then `## Summary`. Omit "
   "rejected or unsupported candidates. If there are no verified defects, say so "
   "explicitly. Do not call either agent again."))

(defn reviewer []
  (k/agent
   {:name "reviewer"
    :model (model-config)
    :instructions (instructions)
    :tools [k/eval]
    :max-turns 5}))

(defn credentials? []
  (or (not (str/blank? (System/getenv "RESPONSES_API_KEY")))
      (not (str/blank? (System/getenv "OPENAI_API_KEY")))))

(defn run-review!
  ([change]
   (run-review! change (k/monitor {:display :tree})))
  ([change monitor]
   (k/run! (reviewer) change
           {:on-event monitor
            :limits {:model-calls 12
                     :evals 2
                     :depth 2
                     :concurrency 8
                     :deadline-ms 300000}})))

(def default-change
  (str
   "Review target: PR #87 — add a customer export endpoint\n\n"
   "Requirement:\n"
   "- Only users with the `:admin` role may export customer data.\n"
   "- Other authenticated users must receive 403.\n\n"
   "Existing behavior:\n"
   "- `auth/require-user` authenticates the request and returns the user.\n"
   "- It does not check roles or permissions.\n\n"
   "```diff\n"
   "diff --git a/src/app/routes.clj b/src/app/routes.clj\n"
   "index 2ad1c8b..7bc214e 100644\n"
   "--- a/src/app/routes.clj\n"
   "+++ b/src/app/routes.clj\n"
   "@@ -18,0 +19,5 @@\n"
   "+\n"
   "+(defn export-customers [request]\n"
   "+  (let [user (auth/require-user request)]\n"
   "+    {:status 200\n"
   "+     :body (csv/encode (customers/all))}))\n"
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

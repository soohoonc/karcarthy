(ns example.composition
  "A known security-review pipeline coordinated with ordinary Clojure."
  (:require [karcarthy :as k]))

(def triager
  (k/agent
   {:name "triager"
    :model "gpt-5.6"
    :instructions
    (str "Read the proposed change and identify the affected trust boundaries. "
         "Return the relevant files, security-sensitive operations, and the "
         "review focus warranted by the change. Do not write findings.")}))

(def security-reviewer
  (k/agent
   {:name "security-reviewer"
    :model "gpt-5.6"
    :instructions
    (str "Review authentication, authorization, sensitive-data access, and "
         "untrusted input. Return only candidate vulnerabilities introduced by "
         "the patch. For each candidate include severity, file and line, a "
         "concrete exploit scenario, evidence, and a minimal fix.")}))

(def verifier
  (k/agent
   {:name "verifier"
    :model "gpt-5.6"
    :instructions
    (str "Independently try to disprove each candidate finding against the "
         "supplied requirements and code. Return CONFIRMED, REJECTED, or "
         "NEEDS CONTEXT for each candidate with exact evidence. End with only "
         "the confirmed findings in review-ready form.")}))

(defn review-change [change]
  (let [triage
        (k/output (k/run! triager change))
        candidates
        (k/output
         (k/run! security-reviewer
                 (str change "\n\nTriage:\n" triage)))]
    (k/output
     (k/run! verifier
             (str change
                  "\n\nTriage:\n" triage
                  "\n\nCandidate findings:\n" candidates)))))

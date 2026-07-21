(ns example.composition
  "A known code-review team coordinated with ordinary Clojure."
  (:require [karcarthy :as k]))

(def correctness-reviewer
  (k/agent
   {:name "correctness-reviewer"
    :model "gpt-5.6"
    :instructions (str "Review the change for concrete behavioral defects. For each finding, "
                       "give severity, file and line, a failure scenario, and a minimal fix. "
                       "Do not report style preferences.")
    :input-schema string?
    :output-schema string?}))

(def concurrency-reviewer
  (k/agent
   {:name "concurrency-reviewer"
    :model "gpt-5.6"
    :instructions (str "Review the change for race conditions, atomicity errors, and unsafe "
                       "shared-state assumptions. Report only actionable defects with a "
                       "failure scenario and minimal fix.")
    :input-schema string?
    :output-schema string?}))

(def test-reviewer
  (k/agent
   {:name "test-reviewer"
    :model "gpt-5.6"
    :instructions (str "Identify missing tests that would expose a concrete defect in the "
                       "change. Give the smallest useful test scenario. Do not propose broad "
                       "coverage or style improvements.")
    :input-schema string?
    :output-schema string?}))

(def review-editor
  (k/agent
   {:name "review-editor"
    :model "gpt-5.6"
    :instructions (str "Deduplicate the reviewer reports. Return only concrete defects, ordered "
                       "by severity, with file and line, failure scenario, and minimal fix. "
                       "End with a short summary.")
    :input-schema string?
    :output-schema string?}))

(defn review-change [change]
  (let [reports
        (->> [correctness-reviewer concurrency-reviewer test-reviewer]
             (mapv #(future (k/run! % change)))
             (mapv (comp k/output deref)))
        editor-input
        (str "Proposed change:\n" change
             "\n\nCorrectness review:\n" (reports 0)
             "\n\nConcurrency review:\n" (reports 1)
             "\n\nTest review:\n" (reports 2))]
    (k/output (k/run! review-editor editor-input))))

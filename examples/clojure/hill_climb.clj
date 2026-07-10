;; A deterministic local companion to the Harbor hill-climbing example.
;; It evaluates three runtime-generated Agent programs, scores exact task
;; outcomes, and retains the highest-scoring candidate.
(require '[karcarthy.demo.dynamic :as dynamic])

(dynamic/run-hill-climb-demo!)

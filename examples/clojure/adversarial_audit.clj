;; Adversarial live stress test for karcarthy's Codex and Claude runners.
;;
;; The workflow deliberately asks for a decision that should be hard to pass:
;; route the request, plan independent repository audits, inspect and challenge
;; each track in parallel, synthesize a verdict, then let a strict critic force
;; one complete revision when evidence is missing.
;;
;; It is offline and deterministic by default. Live runs make multiple paid
;; model calls and may take several minutes:
;;
;;   KARCARTHY_STRESS_RUNNER=codex \
;;     clojure -M -e '(load-file "examples/clojure/adversarial_audit.clj")'
;;
;;   KARCARTHY_STRESS_RUNNER=claude \
;;     clojure -M -e '(load-file "examples/clojure/adversarial_audit.clj")'

(ns examples.clojure.adversarial-audit
  (:require [clojure.pprint :as pp]
            [karcarthy :as k]))

(def audit-contract
  (str
   "Audit the current karcarthy repository for this production scenario:\n"
   "a regulated company wants to use one coordinator process for a 50-agent "
   "compliance report, with expensive CLI-backed leaf agents and a requirement "
   "that partial failure must not silently produce an approved report.\n\n"
   "The decision must cover bounded concurrency, timeouts and cancellation, "
   "failure propagation, retry behavior, process cleanup, persistence/resume, "
   "runner parity, schema validation, observability, and test evidence. "
   "Repository claims require file:line citations. Unknowns must remain unknown."))

(k/defagent request-router
  {:instructions
   (str "Classify the request. Reply with EDN only: {:route :audit} when it asks "
        "for a repository-backed production-readiness audit; otherwise reply "
        "{:route :unsupported}. Do not solve the task.")})

(k/defagent audit-planner
  {:instructions
   (str "Plan exactly three independent repository-audit tracks. Together they "
        "must cover interpreter/failure semantics, subprocess/concurrency/resource "
        "behavior, and schemas/observability/tests. Reply with EDN only: "
        "{:subtasks [\"...\" \"...\" \"...\"]}. Each string must include the "
        "production scenario and concrete questions; workers receive no other context.")})

(k/defagent repository-investigator
  {:instructions
   (str "Act as a staff-level reliability investigator. Inspect the current "
        "repository without editing it. Answer only the assigned audit track. "
        "Separate verified behavior, risks, and unknowns. Cite every repository "
        "claim as path:line and do not infer production guarantees from names or docs.")})

(k/defagent evidence-skeptic
  {:instructions
   (str "Adversarially verify the investigator report against the current repository. "
        "Correct unsupported conclusions, identify missing failure cases, and retain "
        "only findings backed by path:line evidence. Return concise Markdown sections: "
        "Verified, Rejected or weakened, Blocking unknowns, and Track verdict.")})

(k/defagent audit-synthesizer
  {:instructions
   (str "You receive EDN containing the original request, subtasks, and challenged "
        "track results. Produce a production-readiness memo. Required sections: "
        "Verdict, Evidence matrix, Failure scenarios, Blocking gaps, and Minimum "
        "safe deployment plan. Use only supplied evidence, preserve disagreements, "
        "and cite repository findings as path:line. A cautious no-go is preferable "
        "to an unsupported approval.")})

(k/defagent acceptance-critic
  {:instructions
   (str "Judge whether the audit is safe to hand to a regulated production owner. "
        "Accept only if it explicitly covers bounded concurrency, timeouts/cancellation, "
        "failure propagation, retries, process cleanup, persistence/resume, runner parity, "
        "schema validation, observability, and tests; distinguishes evidence from unknowns; "
        "and gives a justified verdict with path:line citations. Reply with EDN only: "
        "{:accept? true} or {:accept? false :feedback \"specific missing evidence\"}.")})

(k/defagent unsupported-request
  {:instructions "Explain briefly that this stress workflow only handles repository-backed production audits."})

(def audit-worker
  (k/pipe repository-investigator evidence-skeptic))

(def audited-decision
  (k/revise
   (k/reduce
    (k/delegate audit-planner audit-worker :max-concurrency 3)
    audit-synthesizer)
   acceptance-critic
   :max-rounds 2))

(def workflow
  (k/route request-router
           {:audit audited-decision}
           :default unsupported-request))

(def offline-runner
  (k/mock-runner
   (fn [{:keys [agent]}]
     (case (:name agent)
       "request-router" "{:route :audit}"
       "audit-planner" (pr-str {:subtasks
                                 ["Interpreter and failure-propagation semantics"
                                  "Subprocess, concurrency, timeout, cancellation, and cleanup behavior"
                                  "Schema validation, observability, runner parity, persistence, and tests"]})
       "repository-investigator" "Verified behavior with src/karcarthy/orchestrate.clj:196. Risks and unknowns remain."
       "evidence-skeptic" "## Verified\nEvidence was challenged.\n## Blocking unknowns\nLive runner parity.\n## Track verdict\nNot production-proven."
       "audit-synthesizer" (str "## Verdict\nNo-go without bounded live testing.\n\n"
                                "## Evidence matrix\nAll required dimensions remain explicit.\n\n"
                                "## Failure scenarios\nPartial failure must block approval.\n\n"
                                "## Blocking gaps\nLive runner parity and persistence.\n\n"
                                "## Minimum safe deployment plan\nCanary with external durable state.")
       "acceptance-critic" "{:accept? true}"
       "unsupported-request" "This workflow handles repository audits only."))))

(defn selected-runner [runner-name]
  (case runner-name
    "offline"
    offline-runner

    "codex"
    (k/codex-runner {:dir "."
                     :sandbox :read-only
                     :timeout-ms (* 10 60 1000)})

    "claude"
    (k/claude-runner {:dir "."
                      :system-prompt-mode :replace
                      :max-turns 8
                      :timeout-ms (* 10 60 1000)
                      :extra-args ["--disallowedTools"
                                   "Bash,Edit,Write,WebSearch,WebFetch,Task,TodoWrite"]})

    (throw (ex-info "unknown KARCARTHY_STRESS_RUNNER"
                    {:runner runner-name
                     :supported ["offline" "codex" "claude"]}))))

(defn execution-summary [events result elapsed-ms]
  (let [agent-starts (filter #(and (= :agent (:kind %))
                                   (= :start (:event %)))
                             events)]
    {:ok? (k/ok? result)
     :accepted? (:accepted? result)
     :rounds (:rounds result)
     :leaf-calls (count agent-starts)
     :elapsed-ms elapsed-ms
     :paths (mapv :path agent-starts)}))

(defn -main [& _]
  (let [runner-name (or (System/getenv "KARCARTHY_STRESS_RUNNER") "offline")
        events (atom [])
        started (System/nanoTime)
        result (k/run {:runner (selected-runner runner-name)
                       :workflow workflow
                       :input audit-contract
                       :options {:observe #(swap! events conj %)}})
        elapsed-ms (/ (double (- (System/nanoTime) started)) 1000000.0)]
    (println "=== Adversarial repository audit ===")
    (pp/pprint {:runner runner-name
                :workflow-valid? (k/workflow? workflow)})
    (println "\n=== Execution ===")
    (pp/pprint (execution-summary @events result elapsed-ms))
    (println "\n=== Result ===")
    (println (:text result))))

(-main)

(shutdown-agents)

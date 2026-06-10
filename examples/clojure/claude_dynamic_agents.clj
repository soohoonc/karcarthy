;; Claude dynamic-agent style orchestration as karcarthy data.
;;
;; Run offline:
;;   clojure -M -e '(load-file "examples/clojure/claude_dynamic_agents.clj")'
;;
;; Run live through Claude CLI, if installed and authenticated:
;;   KARCARTHY_CLAUDE_LIVE=1 clojure -M -e '(load-file "examples/clojure/claude_dynamic_agents.clj")'
;;
;; This mirrors the Claude Code dynamic-workflow pattern in karcarthy terms:
;; a lead agent sharpens its own instructions, a planner creates workstreams at
;; runtime, focused agents run over those workstreams in parallel, and a critic
;; accepts or sends the report back for one revision.

(ns examples.clojure.claude-dynamic-agents
  (:require [clojure.pprint :as pp]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [karcarthy :as k]
            [karcarthy.self :as self]))

(defn bullets [items]
  (str/join "\n" (map #(str "- " %) items)))

(defn section [title items]
  (when (seq items)
    (str title ":\n" (bullets items))))

(def migration-context
  ["System: a payments company is migrating card-token vaulting from a legacy service to a new regionalized platform."
   "Constraints: PCI scope must not expand; regional data residency must be preserved; checkout p95 cannot regress by more than 30 ms; rollback must be reversible per tenant."
   "Known risks: token replay semantics differ, old analytics jobs read from the vault directly, and support needs migration-visible error codes."
   "Audience: staff engineers, security, compliance, support, and the launch owner."])

(defn instructions
  [{:keys [role mission responsibilities output boundaries self-check]}]
  (str/join
   "\n\n"
   (remove str/blank?
           [(str "Role: " role)
            (str "Mission: " mission)
            (section "Operating context" migration-context)
            (section "Responsibilities" responsibilities)
            (str "Output contract:\n" output)
            (section "Boundaries" boundaries)
            (section "Before finalizing, verify" self-check)])))

(defn configured-agent [{:keys [name tools model] :as profile}]
  (k/agent
   (cond-> {:name name
            :instructions (instructions profile)
            :tools (vec tools)}
     model (assoc :model model))))

(def adaptive-lead
  (configured-agent
   {:name "adaptive-lead"
    :role "Dynamic workflow lead"
    :mission "Clarify the migration objective before dispatching parallel agents."
    :responsibilities ["If the current instructions are too weak, patch yourself before answering."
                       "Frame the task as an evidence-backed migration readiness assessment."
                       "Identify the workstreams that a planner should split apart."]
    :output "Either emit a karcarthy self-patch, or return a compact mission frame for downstream agents."
    :boundaries ["Do not claim to have inspected files or systems."
                 "Do not invent owners, dates, or metrics."]
    :self-check ["The mission frame names success criteria, risks, and expected deliverable."]}))

(def planner
  (configured-agent
   {:name "workstream-planner"
    :role "Parallel workstream planner"
    :mission "Break the mission frame into independent Claude subagent workstreams."
    :responsibilities ["Create workstreams that can run without shared mutable context."
                       "Separate architecture, data, operations, compliance, and customer impact."
                       "Keep each subtask concrete enough for a single focused agent pass."]
    :output "Reply with EDN only: {:subtasks [\"...\" \"...\" ...]}. Use exactly five subtasks."
    :boundaries ["Do not solve the subtasks." "Do not include prose outside EDN."]
    :self-check ["Every subtask is independently executable."]}))

(def evidence-scout
  (configured-agent
   {:name "evidence-scout"
    :role "Migration evidence scout"
    :mission "Collect the facts and assumptions needed for one workstream."
    :tools ["Read" "Grep" "Glob"]
    :responsibilities ["List known facts from the mission and workstream."
                       "Name missing evidence that blocks a production decision."
                       "Avoid turning absence of evidence into approval."]
    :output "Markdown bullets: Known evidence, Missing evidence, Decision pressure."
    :boundaries ["No uncited or invented facts." "No recommendations yet."]
    :self-check ["Separates known facts from assumptions."]}))

(def impact-analyst
  (configured-agent
   {:name "impact-analyst"
    :role "Blast-radius analyst"
    :mission "Translate the evidence packet into concrete migration risk."
    :responsibilities ["Identify user-visible, operational, security, and compliance failure modes."
                       "Rank each failure mode by likelihood and impact."
                       "Propose one containment or rollback control for each high-risk item."]
    :output "Markdown table with columns: Risk, Why it matters, Likelihood, Impact, Control."
    :boundaries ["Do not approve launch." "Do not hide unresolved assumptions."]
    :self-check ["Every high-risk item has a control."]}))

(def verifier
  (configured-agent
   {:name "verifier"
    :role "Readiness verifier"
    :mission "Challenge the workstream analysis before it is merged."
    :responsibilities ["Reject claims that are not grounded in the mission or evidence packet."
                       "Flag missing tests, monitors, rollback gates, and communication gaps."
                       "Preserve dissent for synthesis."]
    :output "Markdown bullets: Accept, Challenge, Required follow-up."
    :boundaries ["Do not rewrite the final report." "Do not accept unsupported claims."]
    :self-check ["The follow-up is actionable in one meeting."]}))

(def integrator
  (configured-agent
   {:name "integrator"
    :role "Dynamic-agent synthesis lead"
    :mission "Merge all verified workstreams into a production-readiness memo."
    :responsibilities ["State a go/no-go recommendation."
                       "Preserve workstream disagreements."
                       "Produce an action plan with gates and rollback controls."]
    :output "Markdown sections: Recommendation, Evidence map, Blocking gaps, Rollback plan, Next meeting agenda."
    :boundaries ["Do not invent owners, dates, or measured results."]
    :self-check ["The memo is useful even if the recommendation is no-go."]}))

(def critic
  (configured-agent
   {:name "critic"
    :role "Acceptance critic"
    :mission "Decide whether the dynamic-agent memo is ready for a migration review."
    :responsibilities ["Accept only if the memo has a recommendation, evidence map, blocking gaps, and rollback plan."
                       "Reject if it smooths over missing evidence or invents facts."]
    :output "Reply with EDN only: {:accept? true} or {:accept? false :feedback \"specific revision instructions\"}."
    :boundaries ["Do not rewrite the memo yourself."]
    :self-check ["The final output is exactly one EDN map."]}))

(def workstream
  (k/pipe evidence-scout impact-analyst verifier))

(def dynamic-team
  (k/reduce
   (k/delegate planner workstream :max-concurrency 5)
   integrator))

(def workflow
  (k/revise
   (k/pipe (self/evolve adaptive-lead :max-rounds 3)
           dynamic-team)
   critic
   :max-rounds 2))

(def evolved-instructions
  (str (:instructions adaptive-lead)
       "\n\nDYNAMIC-ORCHESTRATION-CONTRACT:\n"
       "- State migration success criteria before dispatching workers.\n"
       "- Demand evidence for latency, PCI scope, data residency, rollback, and support readiness.\n"
       "- Treat unknown owner/date/metric fields as explicit gaps, not assumptions.\n"
       "- Return a mission frame that downstream agents can use as their only context."))

(def offline-runner
  (k/mock-runner
   (fn [{:keys [agent prompt]}]
     (case (:name agent)
       "adaptive-lead"
       (if (str/includes? (:instructions agent) "DYNAMIC-ORCHESTRATION-CONTRACT")
         (str "Mission frame: assess whether the card-token vault migration is ready for a tenant-gated production rollout.\n"
              "Success criteria: no PCI scope expansion, regional residency preserved, p95 checkout latency within 30 ms, and reversible tenant rollback.\n"
              "Expected deliverable: recommendation, evidence map, blocking gaps, rollback plan, and review agenda.")
         (pr-str {:karcarthy/patch {:instructions evolved-instructions}
                  :reason "Need explicit readiness contract before dispatching workstreams."}))

       "workstream-planner"
       (pr-str {:subtasks ["Architecture and token-semantics compatibility"
                           "PCI scope and regional data-residency controls"
                           "Checkout latency, monitoring, and rollback gates"
                           "Analytics job dependencies on direct vault reads"
                           "Support playbooks, error codes, and customer messaging"]})

       "evidence-scout"
       (str "Known evidence: " prompt "\n"
            "Missing evidence: measured latency, compliance signoff, analytics dependency inventory, named rollback owner.\n"
            "Decision pressure: the workstream can block GA if not resolved.")

       "impact-analyst"
       (str "| Risk | Why it matters | Likelihood | Impact | Control |\n"
            "| --- | --- | --- | --- | --- |\n"
            "| Unsupported assumption | The migration could change production behavior silently | Medium | High | Gate rollout on explicit evidence |\n"
            "| Rollback ambiguity | Tenant rollback may not reverse downstream side effects | Medium | High | Require rehearsal and owner before launch |\n")

       "verifier"
       (str "Accept: the workstream identifies concrete production gates.\n"
            "Challenge: latency and compliance evidence are still missing.\n"
            "Required follow-up: attach measurements, signoffs, and rollback rehearsal notes.")

       "integrator"
       (str "## Recommendation\n"
            "No-go for broad GA; proceed only with a tenant-gated beta after blocking gaps close.\n\n"
            "## Evidence map\n"
            "- PCI scope, data residency, latency, rollback, analytics, and support all have explicit review paths.\n\n"
            "## Blocking gaps\n"
            "- Measured p95 checkout latency.\n"
            "- Compliance signoff for PCI and residency boundaries.\n"
            "- Inventory of analytics jobs that read the old vault directly.\n"
            "- Named rollback owner and rehearsal evidence.\n\n"
            "## Rollback plan\n"
            "- Keep rollout per tenant, preserve old-token compatibility, and rehearse rollback before any GA cohort.\n\n"
            "## Next meeting agenda\n"
            "- Review measurements, signoffs, dependency inventory, and support readiness.")

       "critic" "{:accept? true}"
       (str "[" (:name agent) "] " prompt)))))

(defn live-claude-runner []
  (let [model (System/getenv "KARCARTHY_CLAUDE_MODEL")]
    (k/claude-cli-runner
     (cond-> {:system-prompt-mode :replace
              :max-turns 6
              :timeout-ms (* 10 60 1000)
              :extra-args ["--disallowedTools"
                           "Bash,Edit,Write,WebSearch,WebFetch,Task,TodoWrite"]}
       (seq model) (assoc :model model)))))

(defn event-summary [events]
  (->> events
       (filter #(= :start (:event %)))
       (mapv #(select-keys % [:kind :name :path]))))

(defn strip-agent-tools [workflow]
  (walk/postwalk
   (fn [x]
     (if (k/agent? x) (dissoc x :tools) x))
   workflow))

(defn -main [& _]
  (let [events (atom [])
        live? (boolean (System/getenv "KARCARTHY_CLAUDE_LIVE"))
        runner (if live? (live-claude-runner) offline-runner)
        runnable-workflow (if live? (strip-agent-tools workflow) workflow)
        result (k/run runner runnable-workflow
                      "Prepare a migration-readiness memo for the card-token vault migration."
                      {:observe #(swap! events conj %)})]
    (println "=== Claude dynamic-agent workflow ===")
    (pp/pprint {:shape "evolve lead -> plan workstreams -> parallel worker pipeline -> synthesize -> critique"
                :valid? (k/workflow? runnable-workflow)
                :live? live?})
    (println "\n=== Result ===")
    (println (:text result))
    (println "\naccepted?" (:accepted? result) "rounds" (:rounds result))
    (println "\n=== Execution shape ===")
    (pp/pprint (event-summary @events))))

(-main)

(shutdown-agents)

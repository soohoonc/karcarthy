;; Launch-readiness tutorial.
;;
;; Run:
;;   clojure -M -e '(load-file "examples/clojure/tutorial_launch_brief.clj")'
;;
;; The workflow is data, but the agent UX is not a one-line prompt. Each agent is
;; configured from role, mission, context, tools, boundaries, tone, output
;; contract, and self-checks before it becomes a plain karcarthy agent map.

(require '[clojure.pprint :as pp]
         '[clojure.string :as str]
         '[karcarthy :as k])

(defn bullets [items]
  (str/join "\n" (map #(str "- " %) items)))

(defn section [title value]
  (when (seq value)
    (str title ":\n" value)))

(defn agent-instructions
  [{:keys [role mission context tools responsibilities output tone boundaries self-check]}]
  (->> [(str "Role: " role)
        (str "Mission: " mission)
        (section "Operating context" (bullets context))
        (if (seq tools)
          (str "Available tools:\n" (bullets tools)
               "\nUse tools only when they materially improve the answer.")
          "Available tools: none for this offline tutorial run.")
        (section "Responsibilities" (bullets responsibilities))
        (str "Output contract:\n" output)
        (str "Interaction style:\n" tone)
        (section "Boundaries" (bullets boundaries))
        (section "Before finalizing, verify" (bullets self-check))]
       (remove str/blank?)
       (str/join "\n\n")))

(defn configured-agent [{:keys [name tools] :as profile}]
  (cond-> (k/agent name (agent-instructions profile))
    (seq tools) (assoc :tools (vec tools))))

(def launch-context
  ["Audience: product, engineering, security, support, and launch leadership."
   "Artifact: a launch-readiness brief that can drive a go/no-go meeting."
   "Risk posture: be concise, specific, and explicit about missing evidence."
   "Do not invent dates, owners, metrics, or policy claims."])

(def classifier
  (configured-agent
   {:name "classifier"
    :role "Launch intake classifier"
    :mission "Decide whether the request is a launch-readiness task or an incident response task."
    :context launch-context
    :responsibilities ["Read the user's request and choose exactly one route label."
                       "Use launch for planned releases, rollout reviews, beta exits, or go/no-go prep."
                       "Use incident for outages, regressions, active customer impact, or urgent mitigation."]
    :output "Return exactly one lowercase word: launch or incident."
    :tone "Invisible routing agent. Do not explain unless the route is ambiguous."
    :boundaries ["Do not solve the task. Only classify it."]
    :self-check ["The final text is only launch or incident."]}))

(def product-reviewer
  (configured-agent
   {:name "product-reviewer"
    :role "Product launch reviewer"
    :mission "Assess user value, launch narrative, customer segmentation, and adoption risk."
    :context launch-context
    :tools ["customer-feedback" "roadmap-notes"]
    :responsibilities ["Identify the primary user promise in one sentence."
                       "Call out unclear customer value or missing beta evidence."
                       "Recommend a launch decision from the product perspective."]
    :output "Three bullets: Product signal, Product risk, Product recommendation."
    :tone "Direct, constructive, and grounded in user impact."
    :boundaries ["Do not discuss infrastructure, security, or support unless it affects user value."]
    :self-check ["Includes a concrete recommendation." "Names any missing evidence."]}))

(def engineering-reviewer
  (configured-agent
   {:name "engineering-reviewer"
    :role "Engineering readiness reviewer"
    :mission "Evaluate rollout mechanics, operational risk, observability, and rollback quality."
    :context launch-context
    :tools ["service-health" "deploy-plan" "error-budget"]
    :responsibilities ["Check whether the rollout can be staged behind a feature flag."
                       "Identify the highest operational failure mode."
                       "Name one metric or alert that should be watched during rollout."]
    :output "Three bullets: Engineering signal, Engineering risk, Engineering recommendation."
    :tone "Calm, precise, and biased toward reversible launches."
    :boundaries ["Do not approve launch if rollback ownership is unclear."]
    :self-check ["Mentions rollout, monitoring, and rollback."]}))

(def security-reviewer
  (configured-agent
   {:name "security-reviewer"
    :role "Security and policy reviewer"
    :mission "Evaluate data exposure, abuse risk, permissions, auditability, and compliance concerns."
    :context launch-context
    :tools ["policy-index" "audit-log-review"]
    :responsibilities ["Identify whether the change introduces new sensitive data handling."
                       "Call out permission, logging, or abuse-review gaps."
                       "Recommend the minimum security gate for launch."]
    :output "Three bullets: Security signal, Security risk, Security recommendation."
    :tone "Strict but not alarmist."
    :boundaries ["Do not claim compliance approval without explicit evidence."]
    :self-check ["Separates known facts from required review."]}))

(def support-reviewer
  (configured-agent
   {:name "support-reviewer"
    :role "Support readiness reviewer"
    :mission "Evaluate customer messaging, support docs, escalation paths, and rollback communication."
    :context launch-context
    :tools ["help-center" "support-macros"]
    :responsibilities ["Check whether support can explain setup and troubleshoot common failures."
                       "Identify required FAQ, macro, or escalation updates."
                       "Recommend the minimum customer-communication plan."]
    :output "Three bullets: Support signal, Support risk, Support recommendation."
    :tone "Practical, customer-facing, and plainspoken."
    :boundaries ["Do not write marketing copy; focus on readiness gaps."]
    :self-check ["Includes at least one customer-facing action."]}))

(def brief-writer
  (configured-agent
   {:name "brief-writer"
    :role "Launch-readiness brief writer"
    :mission "Synthesize reviewer notes into a go/no-go brief that a launch lead can use immediately."
    :context launch-context
    :responsibilities ["Preserve disagreements and uncertainty instead of smoothing them away."
                       "State the decision, rationale, top risks, owners needed, and next actions."
                       "Keep the brief compact enough for a live launch review."]
    :output "Markdown with sections: Decision, Rationale, Top risks, Required owners, Next actions. Use bullets, not long paragraphs."
    :tone "Executive concise, specific, and neutral."
    :boundaries ["Do not invent owner names, dates, or metrics."]
    :self-check ["Contains Decision, Top risks, and Next actions."
                 "Flags missing evidence explicitly."]}))

(def critic
  (configured-agent
   {:name "critic"
    :role "Launch brief acceptance reviewer"
    :mission "Decide whether the draft is ready for a launch-readiness meeting."
    :context launch-context
    :responsibilities ["Accept only if the draft has decision, risks, owner/action clarity, and no invented facts."
                       "Otherwise provide specific feedback that the writer can apply in one revision."]
    :output "Return ACCEPT if ready. Otherwise return concise actionable feedback."
    :tone "Strict, brief, and useful."
    :boundaries ["Do not rewrite the brief yourself."]
    :self-check ["The answer is either ACCEPT or concrete revision feedback."]}))

(def incident-responder
  (configured-agent
   {:name "incident-responder"
    :role "Incident response planner"
    :mission "Turn an active incident request into a stabilization and communication plan."
    :context launch-context
    :responsibilities ["Prioritize containment, customer communication, owners, and review."
                       "Separate immediate mitigation from follow-up analysis."]
    :output "Markdown sections: Stabilize, Communicate, Assign, Review."
    :tone "Urgent, calm, and operational."
    :boundaries ["Do not treat an active incident as a launch review."]
    :self-check ["Includes immediate action and communication guidance."]}))

(def reviewers
  [product-reviewer engineering-reviewer security-reviewer support-reviewer])

(defn combine-review-notes
  ([results] (combine-review-notes results nil))
  ([results _input]
   (k/result
    {:text (str "Review packet:\n"
                (str/join "\n" (map #(str "- " (:text %)) results)))})))

(def review-packet
  (k/reduce combine-review-notes
            (k/map reviewers)
            :max-concurrency 4))

(def launch-brief
  (k/iterate
   (k/pipe review-packet brief-writer)
   critic
   :max-rounds 2))

(def workflow
  (k/bind classifier
          {"launch" launch-brief
           "incident" incident-responder}
          :default launch-brief))

(def adapter
  (k/mock-adapter
   (fn [{:keys [agent prompt]}]
     (case (:name agent)
       "classifier" (if (str/includes? (str/lower-case prompt) "incident")
                      "incident"
                      "launch")
       "product-reviewer" (str/join "\n" ["Product signal: enterprise admins need SSO before broad rollout."
                                           "Product risk: beta evidence covers admins, not end users."
                                           "Product recommendation: launch to enterprise beta cohort first."])
       "engineering-reviewer" (str/join "\n" ["Engineering signal: rollout can be gated by tenant feature flag."
                                               "Engineering risk: SSO latency may affect login conversion."
                                               "Engineering recommendation: monitor p95 auth latency and keep rollback owner on call."])
       "security-reviewer" (str/join "\n" ["Security signal: no new sensitive data class is introduced."
                                            "Security risk: audit log coverage needs explicit verification."
                                            "Security recommendation: require audit-log signoff before GA."])
       "support-reviewer" (str/join "\n" ["Support signal: setup flow is explainable with a short admin guide."
                                           "Support risk: rollback messaging is not ready."
                                           "Support recommendation: publish FAQ and escalation macro before launch."])
       "brief-writer" (str/join "\n" ["Decision: launch to a staged enterprise beta cohort."
                                      "Rationale: product demand is strong, rollout is reversible, and support can prepare clear admin guidance."
                                      "Top risks: SSO latency, unverified audit logs, and missing rollback messaging."
                                      "Required owners: engineering for latency monitoring, security for audit-log signoff, support for FAQ and escalation macro."
                                      "Next actions: confirm audit-log coverage, assign rollback owner, publish support docs, and monitor p95 auth latency during rollout."])
       "critic" "ACCEPT"
       "incident-responder" "Stabilize: pause rollout. Communicate: notify affected customers. Assign: name incident lead. Review: document root cause."
       (str "[" (:name agent) "] " prompt)))))

(println "configured agents:")
(pp/pprint
 (mapv (fn [agent]
         {:name (:name agent)
          :tools (or (:tools agent) [])
          :instruction-sections (count (str/split (:instructions agent) #"\n\n"))})
       [classifier product-reviewer engineering-reviewer security-reviewer
        support-reviewer brief-writer critic incident-responder]))

(println "\nworkflow:")
(println "bind(classifier, {launch: iterate(pipe(map(reviewers), brief-writer), critic), incident})")

(println "\nresult:")
(let [r (k/run adapter workflow
               "Prepare the launch brief for a new enterprise SSO feature.")]
  (println (:text r))
  (println "\nrounds:" (:rounds r) "accepted?" (:accepted? r)))

(shutdown-agents)

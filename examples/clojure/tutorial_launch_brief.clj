;; Non-trivial launch-readiness tutorial.
;;
;; Run:
;;   clojure -M -e '(load-file "examples/clojure/tutorial_launch_brief.clj")'

(require '[clojure.pprint :as pp]
         '[clojure.string :as str]
         '[karcarthy :as k])

(k/defagent classifier
  "Classify the request as launch or incident. Reply with one word.")

(k/defagent product-reviewer "Review launch value and user impact.")
(k/defagent engineering-reviewer "Review operational risk and rollout plan.")
(k/defagent security-reviewer "Review policy, data, and abuse risk.")
(k/defagent support-reviewer "Review support readiness and customer messaging.")
(k/defagent brief-writer "Write the final launch-readiness brief.")
(k/defagent incident-responder "Write an incident response plan.")

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
   (fn [draft _input]
     (if (and (str/includes? (:text draft) "Decision")
              (str/includes? (:text draft) "Risks"))
       {:accept? true}
       {:accept? false
        :feedback "Add Decision, Risks, and Next actions sections."}))
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
       "product-reviewer" "Product: strong user value; beta users asked for this."
       "engineering-reviewer" "Engineering: ship behind a flag; watch latency."
       "security-reviewer" "Security: no new sensitive data; keep audit logs."
       "support-reviewer" "Support: update FAQ and prepare rollback messaging."
       "brief-writer" (str "Decision: launch with staged rollout.\n"
                           "Signals:\n" prompt "\n"
                           "Risks: latency regression, unclear support docs.\n"
                           "Next actions: owner signoff, flag rollout, monitor.")
       "incident-responder" "Incident plan: stabilize, communicate, then review."
       (str "[" (:name agent) "] " prompt)))))

(println "workflow:")
(pp/pprint workflow)

(println "\nresult:")
(let [r (k/run adapter workflow
               "Prepare the launch brief for a new enterprise SSO feature.")]
  (println (:text r))
  (println "\nrounds:" (:rounds r) "accepted?" (:accepted? r)))

(shutdown-agents)

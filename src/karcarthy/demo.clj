(ns karcarthy.demo
  "A runnable, fully offline demonstration:

      clojure -M -m karcarthy.demo

  Shows the central idea — a karcarthy workflow is *data* — plus the DSL sugar
  (`defagent` / `defflow`) and orchestrator-workers fanning out over the command
  harness with real subprocesses. No API key or network required."
  (:require [clojure.pprint :as pp]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [karcarthy.core :as k]
            [karcarthy.orchestrate :as o]
            [karcarthy.harness.command :as cmd]))

;; --- Agents via defagent: the var name becomes the agent name --------------
(k/defagent triage    "Classify the request as billing, technical or general. Reply with one word." :model "haiku")
(k/defagent billing   "Resolve billing questions concisely."            :model "haiku")
(k/defagent technical "Diagnose the technical issue and propose a fix." :model "sonnet")
(k/defagent general   "Answer general questions helpfully."             :model "haiku")
(k/defagent reviewer  "Critique the draft answer for accuracy."         :model "sonnet")

;; --- A workflow via defflow: validated at load time ------------------------
(o/defflow support-desk
  (o/route triage
           {"billing"   billing
            "technical" (o/chain technical reviewer)   ; draft, then review
            "general"   general}
           :default general))

(defn- canned
  "A mock responder that fakes plausible replies so the demo reads nicely."
  [{:keys [agent prompt]}]
  (case (:name agent)
    "triage"    "technical"
    "technical" (str "Likely a cache misconfig behind: " prompt)
    "reviewer"  (str "Reviewed OK: " prompt)
    (str "[" (:name agent) "] " prompt)))

(defn- agents-in [flow]
  (->> flow (tree-seq coll? seq) (filter k/agent?)))

(defn -main [& _]
  (println "\n=== A workflow is data (EDN) ===")
  (pp/pprint support-desk)

  (println "\n=== Transform the flow as data: bump every agent to opus ===")
  (let [opusified (walk/postwalk #(if (k/agent? %) (assoc % :model "opus") %)
                                 support-desk)]
    (println "before:" (mapv :model (agents-in support-desk)))
    (println "after: " (mapv :model (agents-in opusified))))

  (println "\n=== Run the support desk on the offline mock harness ===")
  (let [r (o/run-flow (k/mock-harness canned) support-desk
                      "my deploy 500s intermittently")]
    (println "ok?  " (k/ok? r))
    (println "text:" (:text r)))

  (println "\n=== Orchestrate over the command harness (real subprocesses) ===")
  ;; planner splits into words; each worker is `tr a-z A-Z` (uppercase via a
  ;; real subprocess); synthesize rejoins. This actually fans out and gathers.
  (let [shell    (cmd/command-harness ["tr" "a-z" "A-Z"])
        pipeline (o/orchestrate (fn [s] (str/split (str/trim s) #"\s+"))
                                (k/agent "shout" "uppercase")
                                :synthesize (fn [rs _]
                                              (k/result {:text (str/join " " (map :text rs))})))
        r        (o/run-flow shell pipeline "homoiconic agents are data")]
    (println "subtasks:" (:subtasks r))
    (println "result:  " (:text r)))

  (shutdown-agents))

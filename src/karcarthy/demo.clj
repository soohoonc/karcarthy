(ns karcarthy.demo
  "A runnable, fully offline demonstration:

      clojure -M -m karcarthy.demo

  Shows the central idea - a karcarthy workflow is *data* - plus the DSL sugar
  (`defagent` / `defworkflow`), structural rewrites, and structured map/reduce
  with real subprocess workers. No API key or network required."
  (:require [clojure.edn :as edn]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [karcarthy :as k]
            [karcarthy.orchestrate :as o]))

;; --- Agents via defagent: the var name becomes the agent name --------------
(k/defagent triage    "Classify the request. Reply with EDN only: {:route :billing}, {:route :technical}, or {:route :general}." :model "haiku")
(k/defagent billing   "Resolve billing questions concisely."            :model "haiku")
(k/defagent technical "Diagnose the technical issue and propose a fix." :model "sonnet")
(k/defagent general   "Answer general questions helpfully."             :model "haiku")
(k/defagent reviewer  "Critique the draft answer for accuracy."         :model "sonnet")

;; --- A workflow via defworkflow: validated at load time --------------------
(o/defworkflow support-desk
  (o/bind triage
           {:billing   billing
            :technical (o/pipe technical reviewer)   ; draft, then review
            :general   general}
           :default general))

(defn- canned
  "A mock responder that fakes plausible replies so the demo reads nicely."
  [{:keys [agent prompt]}]
  (case (:name agent)
    "triage"    "{:route :technical}"
    "technical" (str "Likely a cache misconfig behind: " prompt)
    "reviewer"  (str "Reviewed OK: " prompt)
    (str "[" (:name agent) "] " prompt)))

(defn -main [& _]
  (println "\n=== A workflow is data (EDN) ===")
  (pp/pprint support-desk)

  (println "\n=== Transform the workflow as data: bump every agent to opus ===")
  (let [opusified (k/config {:model "opus"} support-desk)]
    (println "before:" (mapv :model (k/agents support-desk)))
    (println "after: " (mapv :model (k/agents opusified))))

  (println "\n=== Run the support desk on the offline mock adapter ===")
  (let [r (o/run (k/mock-adapter canned) support-desk
                 "my deploy 500s intermittently")]
    (println "ok?  " (k/ok? r))
    (println "text:" (:text r)))

  (println "\n=== Structured map/reduce with real subprocess workers ===")
  ;; The planner emits EDN data, each worker is `tr a-z A-Z` (uppercase via a
  ;; real subprocess), and the reducer receives the mapped result summary as EDN.
  (let [workflow (o/reduce
                  (o/map (k/agent "split" "Return EDN subtasks." :adapter :plan)
                         (k/agent "shout" "uppercase" :adapter :shell))
                  (k/agent "join" "Join mapped result text." :adapter :join))
        adapter  {:plan  (k/mock-adapter
                          (fn [{:keys [prompt]}]
                            (pr-str {:subtasks (str/split (str/trim prompt) #"\s+")})))
                  :shell (k/command-adapter ["tr" "a-z" "A-Z"])
                  :join  (k/mock-adapter
                          (fn [{:keys [prompt]}]
                            (->> (:results (edn/read-string prompt))
                                 (map :text)
                                 (str/join " "))))}
        r        (o/run adapter workflow "homoiconic agents are data")]
    (println "subtasks:" (get-in r [:mapped :subtasks]))
    (println "result:  " (:text r)))

  (shutdown-agents))

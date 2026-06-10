(ns karcarthy.demo
  "A runnable, fully offline demonstration:

      clojure -M -m karcarthy.demo

  Shows the central idea - a karcarthy workflow is *data* - plus the DSL sugar
  (`defagent` / `defworkflow`), structural rewrites, and structured delegate/reduce
  with real subprocess workers. No API key or network required."
  (:require [clojure.edn :as edn]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [karcarthy :as k]
            [karcarthy.orchestrate :as o]
            [karcarthy.proc :as proc]))

;; --- Agents via defagent: the var name becomes the agent name --------------
(k/defagent triage
  {:instructions "Classify the request. Reply with EDN only: {:route :billing}, {:route :technical}, or {:route :general}."
   :model "haiku"})

(k/defagent billing
  {:instructions "Resolve billing questions concisely."
   :model "haiku"})

(k/defagent technical
  {:instructions "Diagnose the technical issue and propose a fix."
   :model "sonnet"})

(k/defagent general
  {:instructions "Answer general questions helpfully."
   :model "haiku"})

(k/defagent reviewer
  {:instructions "Critique the draft answer for accuracy."
   :model "sonnet"})

;; --- A workflow via defworkflow: validated at load time --------------------
(o/defworkflow support-desk
  (o/route triage
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
  (let [opusified (k/configure {:model "opus"} support-desk)]
    (println "before:" (mapv :model (k/agents support-desk)))
    (println "after: " (mapv :model (k/agents opusified))))

  (println "\n=== Run the support desk on the offline mock runner ===")
  (let [r (k/run {:runner (k/mock-runner canned)
                  :workflow support-desk
                  :input "my deploy 500s intermittently"})]
    (println "ok?  " (k/ok? r))
    (println "text:" (:text r)))

  (println "\n=== Structured delegate/reduce with real subprocess workers ===")
  ;; The planner emits EDN data, each worker is `tr a-z A-Z` (uppercase via a
  ;; real subprocess), and the reducer receives the source result summary as EDN.
  (let [workflow (o/reduce
                  (o/delegate (k/agent {:name "split"
                                        :instructions "Return EDN subtasks."})
                              (k/agent {:name "shout"
                                        :instructions "uppercase"}))
                  (k/agent {:name "join"
                            :instructions "Join source result text."}))
        runner  (k/fn-runner
                 (fn [{:keys [agent input]}]
                   (case (:name agent)
                     "split"
                     (pr-str {:subtasks (str/split (str/trim input) #"\s+")})

                     "shout"
                     (let [{:keys [out]} (proc/run ["tr" "a-z" "A-Z"] {:in input})]
                       out)

                     "join"
                     (->> (:results (edn/read-string input))
                          (map :text)
                          (str/join " "))

                     (str "[" (:name agent) "] " input)))
                 {:call? true})
        r        (k/run {:runner runner
                         :workflow workflow
                         :input "homoiconic agents are data"})]
    (println "subtasks:" (get-in r [:source :subtasks]))
    (println "result:  " (:text r)))

  (shutdown-agents))

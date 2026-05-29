(ns karcarthy.demo
  "A runnable, fully offline demonstration:

      clojure -M -m karcarthy.demo

  It shows the central idea: a karcarthy workflow is *data*. We build a flow,
  print it as EDN, transform it with `clojure.walk` (bump every agent's model),
  then run it on the offline mock harness — no API key or network required."
  (:require [clojure.pprint :as pp]
            [clojure.walk :as walk]
            [karcarthy.core :as k]
            [karcarthy.orchestrate :as o]))

;; --- Agents: data ----------------------------------------------------------
(def triage    (k/agent "triage"    "Classify the request as billing, technical or general. Reply with one word." :model "haiku"))
(def billing   (k/agent "billing"   "Resolve billing questions concisely."        :model "haiku"))
(def technical (k/agent "technical" "Diagnose the technical issue and propose a fix." :model "sonnet"))
(def general   (k/agent "general"   "Answer general questions helpfully."         :model "haiku"))
(def reviewer  (k/agent "reviewer"  "Critique the draft answer for accuracy."     :model "sonnet"))

;; --- A workflow: data ------------------------------------------------------
;; Route by triage. The technical path drafts an answer then reviews it (a
;; chain). Everything here is just maps and vectors.
(def support-desk
  (o/route triage
           {"billing"   billing
            "technical" (o/chain technical reviewer)
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

  (println "\n=== Run it on the offline mock harness ===")
  (let [h (k/mock-harness canned)
        r (o/run-flow h support-desk "my deploy 500s intermittently")]
    (println "ok?  " (k/ok? r))
    (println "text:" (:text r)))

  (shutdown-agents))

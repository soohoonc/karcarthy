;; OpenAI Swarm-style triage handoff, offline.
;;
;; Run:
;;   clojure -M -e '(load-file "examples/clojure/open-source-harnesses/swarm/demo.clj")'

(require '[clojure.pprint :as pp]
         '[clojure.string :as str]
         '[clojure.walk :as walk]
         '[karcarthy :as k])

(defn- mentions? [s word]
  (str/includes? (str/lower-case (str s)) word))

(defn- compact [workflow]
  (walk/postwalk
   (fn [x] (if (k/agent? x) (select-keys x [:karcarthy/type :name]) x))
   workflow))

(k/defagent triage "Classify as refund, sales, or support. Reply with one word.")
(k/defagent refund "Handle refunds.")
(k/defagent sales "Handle sales questions.")
(k/defagent support "Handle general support.")

(def swarm-triage
  (k/handoff-router triage
                    {"refund" refund
                     "sales" sales
                     "support" support}
                    :default support))

(def runner
  (k/mock-runner
   (fn [{:keys [agent prompt]}]
     (case (:name agent)
       "triage"  (cond
                   (mentions? prompt "refund") "refund"
                   (mentions? prompt "price")  "sales"
                   :else                       "support")
       "refund"  "Refund specialist: verify charge id, explain policy, and start reversal."
       "sales"   "Sales specialist: answer pricing and plan-fit questions."
       "support" "Support specialist: gather context and unblock the customer."))))

(println "workflow:")
(pp/pprint (compact swarm-triage))

(println "\nresult:")
(println (:text (k/run runner swarm-triage
                       "I was charged twice and need a refund.")))

(shutdown-agents)

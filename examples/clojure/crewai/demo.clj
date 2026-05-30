;; CrewAI-style agents + tasks + sequential crew, offline.
;;
;; Run:
;;   clojure -M -e '(load-file "examples/clojure/crewai/demo.clj")'

(require '[clojure.pprint :as pp]
         '[clojure.walk :as walk]
         '[karcarthy :as k])

(defn- compact [workflow]
  (walk/postwalk
   (fn [x] (if (k/agent? x) (select-keys x [:karcarthy/type :name]) x))
   workflow))

(k/defagent researcher "Research the market signal.")
(k/defagent analyst "Analyze risk.")
(k/defagent writer "Write the brief.")

(def crew
  (k/crew [{:agent researcher :id :market :description "Find the market signal."}
           {:agent analyst :id :risk :description "Name the main risk."}
           {:agent writer :id :brief :description "Write the final brief."}]))

(def runner
  (k/mock-runner
   (fn [{:keys [agent prompt]}]
     (case (:name agent)
       "researcher/market" "Finding: developers already know these harness shapes."
       "analyst/risk"      "Risk: naming drift creates more confusion than missing features."
       "writer/brief"      (str "Brief: " prompt)))))

(println "workflow:")
(pp/pprint (compact crew))

(println "\nresult:")
(println (:text (k/run runner crew "Show why karcarthy is useful.")))

(shutdown-agents)

;; AutoGen-style round-robin group chat, offline.
;;
;; Run:
;;   clojure -M -e '(load-file "examples/clojure/open-source-harnesses/autogen/demo.clj")'

(require '[clojure.pprint :as pp]
         '[clojure.string :as str]
         '[clojure.walk :as walk]
         '[karcarthy :as k])

(defn- compact [workflow]
  (walk/postwalk
   (fn [x] (if (k/agent? x) (select-keys x [:karcarthy/type :name]) x))
   workflow))

(defn- append-line [transcript speaker line]
  (str (str/trim (str transcript)) "\n" speaker ": " line))

(k/defagent planner "Plan the example.")
(k/defagent builder "Build the example.")
(k/defagent reviewer "Review the example.")

(def chat
  (k/group-chat [planner builder reviewer] :rounds 3))

(def runner
  (k/mock-runner
   (fn [{:keys [agent prompt]}]
     (case (:name agent)
       "planner"  (append-line prompt "Planner" "Pick one familiar harness and show the equivalent data.")
       "builder"  (append-line prompt "Builder" "Keep the runner offline so the example is deterministic.")
       "reviewer" (append-line prompt "Reviewer" "Print the workflow data before the result.")))))

(println "workflow:")
(pp/pprint (compact chat))

(println "\nresult:")
(println (:text (k/run runner chat "Plan a tiny harness demo.")))

(shutdown-agents)

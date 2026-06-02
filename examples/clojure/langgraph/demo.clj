;; LangGraph-style state graph with routed edges, offline.
;;
;; Run:
;;   clojure -M -e '(load-file "examples/clojure/langgraph/demo.clj")'

(require '[clojure.pprint :as pp]
         '[clojure.string :as str]
         '[clojure.walk :as walk]
         '[karcarthy :as k])

(defn- mentions? [s word]
  (str/includes? (str/lower-case (str s)) word))

(defn- compact [workflow]
  (walk/postwalk
   (fn [x]
     (cond
       (k/agent? x) (select-keys x [:karcarthy/type :name])
       (fn? x) :fn
       :else x))
   workflow))

(k/defagent docs "Answer with documentation guidance.")
(k/defagent code "Answer with implementation guidance.")
(k/defagent final "Synthesize graph state.")

(defn classify [{:keys [input]}]
  {:text input
   :route (if (mentions? input "code") :code :docs)})

(def graph
  (k/state-graph
   {:start :classify
    :nodes {:classify classify
            :docs {:workflow docs :prompt :input}
            :code {:workflow code :prompt :input}
            :final {:workflow final
                    :prompt (fn [state]
                              (str "route=" (name (:route state))
                                   "\nworker=" (:text state)))}}
    :edges {:classify {:docs :docs :code :code}
            :docs :final
            :code :final
            :final :end}}))

(def adapter
  (k/mock-adapter
   (fn [{:keys [agent prompt]}]
     (case (:name agent)
       "docs"  "Docs worker: explain with a short snippet."
       "code"  "Code worker: show the shape as data first."
       "final" (str "Final from graph:\n" prompt)))))

(println "workflow:")
(pp/pprint (compact graph))

(println "\nresult:")
(println (:text (k/run adapter graph
                       "Should this example show code or docs first?")))

(shutdown-agents)

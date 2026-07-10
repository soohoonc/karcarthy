;; An offline Agent that constructs and runs a generated calculator Agent.
(require '[karcarthy :as k])

(k/deftool add
  {:description "Add two integers."
   :input map?
   :input-schema {:type "object"
                  :properties {"a" {:type "integer"}
                               "b" {:type "integer"}}
                  :required ["a" "b"]}
   :output int?}
  [{:keys [a b]}]
  (+ a b))

(def calculator-model
  (k/fake-model
   (fn [{:keys [messages]}]
     (if (= :tool (:role (first messages)))
       {:type :final :output (:content (first messages))}
       {:type :tool-calls
        :calls [{:id "add_1" :name "add" :input {:a 20 :b 22}}]}))))

(k/defagent calculator
  {:model {:id "offline" :transport calculator-model}
   :instructions "Use add to calculate the answer."
   :tools [add]
   :output int?})

(k/defagent architect
  {:description "Construct and run a new Agent as ordinary Clojure code."
   :input any?
   :output int?}
  [input]
  (let [source
        "(agent {:name \"generated-calculator\" :input any? :output int?}
                [input]
                (:output (run! calculator input)))"
        generated (k/compile-agent! source)]
    (:output (k/run! generated input))))

(let [run (k/run! architect {:question "What is 20 + 22?"}
                  {:observe #(println (:type %) (or (:agent %) ""))})]
  (println "output:" (:output run))
  (println "generated forms:" (get-in run [:usage :generated-forms])))

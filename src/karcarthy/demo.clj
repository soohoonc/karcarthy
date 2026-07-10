(ns karcarthy.demo
  "Offline demonstration of the native model/tool loop."
  (:require [karcarthy :as k]))

(k/deftool uppercase
  {:description "Uppercase text."
   :input map?
   :output string?}
  [_ {:keys [text]}]
  (.toUpperCase ^String text))

(def demo-model
  (k/fake-model
   (fn [{:keys [input]}]
     (if (= :tool (:role (first input)))
       {:type :final :output (:content (first input))}
       {:type :tool-calls
        :calls [{:id "call_1"
                 :name "uppercase"
                 :input {:text (get-in input [0 :content])}}]}))))

(k/defagent demo-agent
  {:model {:id "offline" :transport demo-model}
   :instructions "Use the uppercase tool."
   :tools [uppercase]
   :output string?})

(defn -main [& [input]]
  (let [run (k/run! demo-agent (or input "hello from karcarthy"))]
    (println (:output run))))

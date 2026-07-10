(ns karcarthy.examples.hello
  "Offline demonstration of the native model/tool loop."
  (:require [karcarthy :as k]))

(k/deftool uppercase
  {:description "Uppercase text."
   :input map?
   :output string?}
  [{:keys [text]}]
  (.toUpperCase ^String text))

(def hello-model
  (k/fake-model
   (fn [{:keys [messages]}]
     (let [input messages]
       (if (= :tool (:role (first input)))
         {:type :final :output (:content (first input))}
         {:type :tool-calls
          :calls [{:id "call_1"
                   :name "uppercase"
                   :input {:text (get-in input [0 :content])}}]})))))

(k/defagent hello-agent
  {:model {:id "offline" :transport hello-model}
   :instructions "Use the uppercase tool."
   :tools [uppercase]
   :output string?})

(defn -main [& [input]]
  (let [run (k/run! hello-agent (or input "hello from karcarthy"))]
    (println (:output run))))

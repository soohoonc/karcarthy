(ns example.composition
  "A known Agent team coordinated with ordinary Clojure."
  (:require [karcarthy :as k]))

(defn output! [run]
  (if (= :completed (:status run))
    (:output run)
    (throw (ex-info "Agent Run failed" {:run run}))))

(def botanist
  (k/agent
   {:name "botanist"
    :model "gpt-5.6"
    :instructions "Find biological causes in the garden report. State the evidence you need."
    :input-schema string?
    :output-schema string?}))

(def radiation-engineer
  (k/agent
   {:name "radiation-engineer"
    :model "gpt-5.6"
    :instructions "Find radiation and shielding causes. State the evidence you need."
    :input-schema string?
    :output-schema string?}))

(def editor
  (k/agent
   {:name "garden-editor"
    :model "gpt-5.6"
    :instructions "Combine the specialist reports into a concise diagnosis and next three checks."
    :input-schema string?
    :output-schema string?}))

(defn diagnose-garden [report]
  (let [[biology radiation]
        (->> [botanist radiation-engineer]
             (mapv #(future (k/run! % report)))
             (mapv deref)
             (mapv output!))
        editor-input
        (str "Garden report:\n" report
             "\n\nBiology:\n" biology
             "\n\nRadiation and shielding:\n" radiation)]
    (output! (k/run! editor editor-input))))

(ns karcarthy.examples.search
  "Compile a candidate Clojure Agent program for direct or Harbor evaluation."
  (:require [clojure.string :as str]
            [karcarthy :as k]
            [karcarthy.examples.coding :as coding]))

(defn candidate-model []
  {:transport :responses
   :provider :openai
   :id (coding/model-id (:model-id (k/context)))
   :reasoning :medium
   :timeout-ms 300000})

(defn candidate-tools []
  (let [cwd (:cwd (k/context))]
    (when (str/blank? cwd)
      (throw (ex-info "Candidate context requires :cwd" {})))
    (k/local-tools {:cwd cwd})))

(defn coding-instructions [] coding/instructions)

(def compiler
  (k/agent
   {:name "candidate-compiler"
    :input string?
    :output k/agent?}
   [source]
   (k/compile-agent! source)))

(defn compile-candidate [context source]
  (let [run (k/run! compiler source
                    {:context context
                     :limits {:agent-forms 1
                              :model-calls 0
                              :deadline-ms 30000}})]
    (if (= :completed (:status run))
      (:output run)
      (throw (ex-info "Candidate Agent did not compile"
                      {:error (:error run)})))))

(defn harbor-agent [context]
  (let [path (System/getenv "KARCARTHY_CANDIDATE_PATH")]
    (when (str/blank? path)
      (throw (ex-info "KARCARTHY_CANDIDATE_PATH is required" {})))
    (compile-candidate context (slurp path))))

(defn validate-file! [path]
  (let [candidate (compile-candidate {:cwd "."} (slurp path))]
    (println "valid:" (:name candidate))))

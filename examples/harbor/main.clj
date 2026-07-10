(ns main
  "Compile a candidate Clojure Agent program for Harbor evaluation."
  (:require [clojure.string :as str]
            [karcarthy :as k]))

(def instructions
  (str
   "Work autonomously on the repository in your tool root. "
   "Inspect the repository and run its tests before deciding what to change. "
   "Diagnose causes rather than patching symptoms, make focused edits, and run "
   "the relevant tests again before answering. "
   "Use additional Agents, parallel work, review, or other strategies when the "
   "task evidence makes them useful; choose the approach yourself. "
   "Finish with a concise account of the cause, changes, and verification."))

(defn model-id [selected]
  (or selected
      (System/getenv "KARCARTHY_OPENAI_MODEL")
      "gpt-5.6"))

(defn candidate-model []
  {:transport :responses
   :provider :openai
   :id (model-id (:model-id (k/context)))
   :reasoning :medium
   :timeout-ms 300000})

(defn candidate-tools []
  (let [cwd (:cwd (k/context))]
    (when (str/blank? cwd)
      (throw (ex-info "Candidate context requires :cwd" {})))
    (k/local-tools {:cwd cwd})))

(defn coding-instructions [] instructions)

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
  (when (str/blank? path)
    (throw (ex-info "Usage: clojure -M -m main validate <candidate.clj>" {})))
  (let [candidate (compile-candidate {:cwd "."} (slurp path))]
    (println "valid:" (:name candidate))))

(defn -main [& [command path]]
  (case command
    "validate" (validate-file! path)
    (do
      (binding [*out* *err*]
        (println "Usage: clojure -M -m main validate <candidate.clj>"))
      (System/exit 2))))

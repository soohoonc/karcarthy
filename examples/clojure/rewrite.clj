;; Rewrite workflow data before running it.
;;
;; Run:
;;   clojure -M -e '(load-file "examples/clojure/rewrite.clj")'
;;
;; This is the Clojure/Lisp move: build one workflow as data, apply ordinary
;; host-language rewrites to that data, then interpret the rewritten value.

(require '[clojure.pprint :as pp]
         '[clojure.string :as str]
         '[karcarthy :as k])

(def researcher
  (k/agent {:name "researcher"
            :instructions "Find the core technical facts."}))

(def writer
  (k/agent {:name "writer"
            :instructions "Explain the facts in plain language."}))

(def workflow
  (k/pipe researcher writer))

(def production-workflow
  (->> workflow
       (k/configure {:runner :primary
                     :model "claude-sonnet-4"
                     :instructions/suffix "State assumptions before final answer."})))

(def runner
  {:primary
   (k/mock-runner
    (fn [{:keys [agent prompt]}]
      (let [last-instruction (last (str/split-lines (:instructions agent)))]
        (str "[" (:name agent) " via " (:model agent) "] "
             last-instruction
             "\n" prompt))))})

(println "=== original workflow ===")
(pp/pprint workflow)

(println "\n=== rewritten agents ===")
(pp/pprint
 (map #(select-keys % [:name :runner :model :instructions])
      (k/agents production-workflow)))

(println "\n=== run rewritten workflow ===")
(println
 (:text
  (k/run runner
         production-workflow
         "Why does representing workflows as EDN matter?")))

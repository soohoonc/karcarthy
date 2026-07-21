(ns karcarthy.eval-test
  (:require [clojure.test :refer [deftest is testing]]
            [karcarthy :as k]
            [karcarthy.eval :as keval]))

(defn scripted-model [& responses]
  (let [remaining (atom responses)]
    (k/mock-model
     (fn [request]
       (let [response (first @remaining)]
         (swap! remaining next)
         (if (fn? response) (response request) response))))))

(defn eval-model [code]
  (scripted-model
   {:type :tool-calls
    :calls [{:id "eval" :name "eval" :input {:code code}}]}
   (fn [request]
     {:type :final :output (get-in request [:messages 0 :content])})))

(def suffix " from the definition namespace")

(deftest reads-exactly-one-expression-with-reader-eval-disabled
  (is (= '(+ 1 2) (keval/read-expression "(+ 1 2)")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"exactly one"
                        (keval/read-expression "1 2")))
  (is (thrown? Throwable (keval/read-expression "#=(System/exit 1)"))))

(deftest eval-resolves-public-vars-from-the-agent-definition-namespace
  (let [parent (k/agent {:name "parent"
                         :model {:transport :parent :id "parent"}
                         :instructions "Use eval."
                         :output-schema string?})
        run (k/run! parent "value"
                    {:model-transports
                     {:parent (eval-model "(str input suffix)")}})]
    (is (= :completed (:status run)) (pr-str (:error run)))
    (is (= (str "value" suffix) (:output run)))))

(deftest eval-runs-ordinary-clojure-and-concurrent-agents-in-one-run
  (let [code
        (str "(let [worker (agent {:name \"worker\" "
             ":model {:transport :worker :id \"worker\"} "
             ":instructions \"Append punctuation.\" "
             ":input-schema string? :output-schema string?}) "
             "jobs (mapv #(future (run! worker %)) input)] "
             "(mapv (comp output deref) jobs))")
        worker-model
        (k/mock-model
         (fn [request]
           {:type :final
            :output (str (get-in request [:messages 0 :content]) "!")}))
        parent (k/agent {:name "parent"
                         :model {:transport :parent :id "parent"}
                         :instructions "Use eval."
                         :output-schema vector?})
        run (k/run! parent ["one" "two"]
                    {:model-transports
                     {:parent (eval-model code)
                      :worker worker-model}})
        eval-events (filter #(= "eval" (namespace (:type %))) (:events run))]
    (is (= :completed (:status run)) (pr-str (:error run)))
    (is (= ["one!" "two!"] (:output run)))
    (is (= 1 (get-in run [:usage :evals])))
    (is (= #{:eval/started :eval/expanded :eval/completed}
           (set (map :type eval-events))))
    (is (= #{(:id run)} (set (map :run-id (:events run)))))
    (is (= ["parent" "worker" "worker"]
           (->> (:events run)
                (filter #(= :agent/started (:type %)))
                (mapv :agent))))))

(deftest run-inside-eval-returns-a-run-map-and-retains-code
  (let [code
        (str "(let [a (agent {:name \"made-here\" "
             ":model {:transport :child :id \"child\"} "
             ":instructions \"Answer.\" :output-schema string?}) "
             "r (run! a input)] "
             "{:same-run? (= (:id r) (:run-id (first (:events r)))) "
             ":status (:status r) :output (:output r) "
             ":definition (pr-str (definition a)) "
             ":expansion (pr-str (expansion a))})")
        parent (k/agent {:name "parent"
                         :model {:transport :parent :id "parent"}
                         :instructions "Use eval."
                         :output-schema map?})
        run (k/run! parent "hello"
                    {:model-transports
                     {:parent (eval-model code)
                      :child (scripted-model "done")}})
        output (:output run)]
    (is (= :completed (:status run)) (pr-str (:error run)))
    (is (= true (:same-run? output)))
    (is (= "completed" (:status output)))
    (is (= "done" (:output output)))
    (is (re-find #"agent" (:definition output)))
    (is (re-find #"make-agent" (:expansion output)))))

(deftest model-authored-agent-can-eval-recursively
  (let [answer-code
        (str "(let [answer (agent {:name \"answer\" "
             ":model {:transport :answer :id \"answer\"} "
             ":instructions \"Return 42.\" :output-schema int?})] "
             "(:output (run! answer input)))")
        child-model (eval-model answer-code)
        parent-code
        (str "(let [child (agent {:name \"child\" "
             ":model {:transport :child :id \"child\"} "
             ":instructions \"Use eval.\" :output-schema int?})] "
             "(:output (run! child input)))")
        parent (k/agent {:name "parent"
                         :model {:transport :parent :id "parent"}
                         :instructions "Use eval."
                         :output-schema int?})
        run (k/run! parent "solve"
                    {:model-transports
                     {:parent (eval-model parent-code)
                      :child child-model
                      :answer (scripted-model 42)}})]
    (is (= :completed (:status run)) (pr-str (:error run)))
    (is (= 42 (:output run)))
    (is (= 2 (get-in run [:usage :evals])))
    (is (= ["parent" "child" "answer"]
           (->> (:events run)
                (filter #(= :agent/started (:type %)))
                (mapv :agent))))))

(deftest eval-limit-and-output-boundary-are-enforced
  (testing "eval budget"
    (let [parent (k/agent {:name "parent"
                           :model {:transport :parent :id "parent"}
                           :instructions "Use eval."
                           :output-schema any?})
          run (k/run! parent nil
                      {:limits {:evals 0}
                       :model-transports
                       {:parent (eval-model "(+ 1 2)")}})]
      (is (= :failed (:status run)))
      (is (= :budget (get-in run [:error :kind])))))
  (testing "JVM objects do not leak to the model"
    (let [parent (k/agent {:name "parent"
                           :model {:transport :parent :id "parent"}
                           :instructions "Use eval."
                           :output-schema any?})
          run (k/run! parent nil
                      {:model-transports
                       {:parent (eval-model "(Object.)")}})]
      (is (= :failed (:status run)))
      (is (= :evaluation (get-in run [:error :kind])))
      (is (= :output (get-in run [:error :phase]))))))

(deftest eval-has-context-and-can-define-an-agent
  (let [code
        (str "(do (defagent made "
             "{:model {:transport :child :id \"child\"} "
             ":instructions \"Answer.\" :output-schema map?}) "
             "(assoc (:output (run! made input)) "
             ":context (context)))")
        parent (k/agent {:name "parent"
                         :model {:transport :parent :id "parent"}
                         :instructions "Use eval."
                         :output-schema map?})
        run (k/run! parent {:task "work"}
                    {:context {:request-id "r1"}
                     :model-transports
                     {:parent (eval-model code)
                      :child (scripted-model
                              {:type :final :output {:seen "work"}})}})]
    (is (= :completed (:status run)) (pr-str (:error run)))
    (is (= {:seen "work" :context {:request-id "r1"}}
           (:output run)))))

(deftest dynamically-started-agents-obey-parallelism
  (let [code
        (str "(let [a (agent {:name \"child\" "
             ":model {:transport :child :id \"child\"} "
             ":instructions \"Answer.\"})] "
             "(:status (run! a input)))")
        parent (k/agent {:name "parent"
                         :model {:transport :parent :id "parent"}
                         :instructions "Use eval."
                         :output-schema string?})
        run (k/run! parent "work"
                    {:limits {:concurrency 1}
                     :model-transports
                     {:parent (eval-model code)
                      :child (scripted-model "unused")}})]
    ;; eval occupies the one concurrent slot, so its attempted Agent call
    ;; returns a failed Run map instead of escaping the active run.
    (is (= :completed (:status run)) (pr-str (:error run)))
    (is (= "failed" (:output run)))
    (is (= ["parent"]
           (->> (:events run)
                (filter #(= :agent/started (:type %)))
                (mapv :agent))))))

(ns karcarthy.eval-test
  (:refer-clojure :exclude [run!])
  (:require [clojure.test :refer [deftest is]]
            [karcarthy :as k]))

(def suffix " from definition namespace")

(defn compile-in-run [source & [limits]]
  (let [compiler (k/agent {:name "compiler" :input string? :output k/agent?}
                          [rt input] (k/compile-agent! rt input))]
    (k/run! compiler source {:limits limits})))

(deftest reads-one-form-with-reader-eval-disabled
  (is (= '(+ 1 2) (k/read-agent-form "(+ 1 2)")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"exactly one"
                        (k/read-agent-form "1 2")))
  (is (thrown? Throwable
               (k/read-agent-form "#=(System/exit 1)"))))

(deftest compiles-and-invokes-generated-agent
  (let [parent (k/agent {:name "parent" :input string? :output string?}
                        [rt input]
                        (let [child (k/compile-agent!
                                     rt
                                     "(agent {:name \"child\" :input string? :output string?} [rt x] (str x \"!\"))")]
                          (k/invoke! rt child input)))
        run (k/run! parent "hello")]
    (is (= :completed (:status run)))
    (is (= "hello!" (:output run)))
    (is (= 1 (get-in run [:usage :generated-forms])))
    (is (= #{:program/read :program/expanded :program/checked
             :program/evaluated}
           (->> (:events run)
                (map :type)
                (filter #(= "program" (namespace %)))
                set)))))

(deftest generated-code-resolves-definition-namespace
  (let [parent (k/agent {:name "parent" :output string?}
                        [rt _]
                        (k/invoke!
                         rt
                         (k/compile-agent!
                          rt
                          "(agent {:name \"child\" :output string?} [rt _] (str \"ok\" suffix))")
                         nil))
        run (k/run! parent nil)]
    (is (= :completed (:status run)))
    (is (= (str "ok" suffix) (:output run)))))

(deftest generated-value-must-be-agent
  (let [run (compile-in-run "(+ 1 2)")]
    (is (= :failed (:status run)))
    (is (= :evaluation (get-in run [:error :kind])))))

(deftest compiler-errors-are-structured
  (let [run (compile-in-run
             "(agent {:name \"bad\" :output string?} [rt _] (missing-symbol))")]
    (is (= :failed (:status run)))
    (is (= :evaluation (get-in run [:error :phase])))
    (is (re-find #"missing-symbol" (get-in run [:error :message])))))

(deftest generated-form-budget
  (let [parent (k/agent {:name "parent" :output string?}
                        [rt _]
                        (k/compile-agent!
                         rt
                         "(agent {:name \"child\" :output string?} [_ _] \"ok\")")
                        "unreachable")
        run (k/run! parent nil {:limits {:generated-forms 0}})]
    (is (= :failed (:status run)))
    (is (= :budget (get-in run [:error :kind])))))

(deftest evaluated-agent-retains-forms
  (let [run (compile-in-run
             "(agent {:name \"child\" :output string?} [_ _] \"ok\")")
        agent (:output run)]
    (is (= :completed (:status run)))
    (is (= 'agent (first (k/source-form agent))))
    (is (= 'karcarthy.core/make-agent
           (first (k/expanded-form agent))))))

(deftest agent-is-its-own-homoiconic-bridge
  (let [calls (atom 0)
        agent-tool (k/agent)
        model
        (k/fake-model
         (fn [request]
           (if (= 1 (swap! calls inc))
             {:type :tool-calls
              :calls
              [{:id "call_eval"
                :name "agent"
                :input
                {:source
                 "(agent {:name \"increment\" :input int? :output int?} [_ n] (inc n))"
                 :input 41}}]}
             {:type :final
              :output (get-in request [:input 0 :content])})))
        parent
        (k/agent {:name "architect"
                  :model {:id "fake" :transport model}
                  :instructions "Write and run an Agent."
                  :tools [agent-tool]
                  :output int?})
        run (k/run! parent nil)]
    (is (= :completed (:status run)))
    (is (= 42 (:output run)))
    (is (= 1 (get-in run [:usage :generated-forms])))
    (is (= ["architect" "increment"]
           (->> (:events run)
                (filter #(= :agent/started (:type %)))
                (map :agent)
                vec)))
    (is (= #{:program/read :program/expanded :program/checked
             :program/evaluated}
           (->> (:events run)
                (map :type)
                (filter #(= "program" (namespace %)))
                set)))))

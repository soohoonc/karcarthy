(ns karcarthy.eval-test
  (:refer-clojure :exclude [run!])
  (:require [clojure.test :refer [deftest is]]
            [karcarthy :as k]))

(def suffix " from definition namespace")

(defn compile-in-run [source & [limits]]
  (let [compiler (k/agent {:name "compiler" :input string? :output k/agent?}
                          [input] (k/compile-agent! input))]
    (k/run! compiler source {:limits limits})))

(deftest reads-one-form-with-reader-eval-disabled
  (is (= '(+ 1 2) (k/read-agent-form "(+ 1 2)")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"exactly one"
                        (k/read-agent-form "1 2")))
  (is (thrown? Throwable
               (k/read-agent-form "#=(System/exit 1)"))))

(deftest compiles-and-runs-generated-agent
  (let [parent (k/agent {:name "parent" :input string? :output string?}
                        [input]
                        (let [child (k/compile-agent!
                                     "(agent {:name \"child\" :input string? :output string?} [x] (str x \"!\"))")]
                          (:output (k/run! child input))))
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
                        [_]
                        (:output
                         (k/run!
                          (k/compile-agent!
                           "(agent {:name \"child\" :output string?} [_] (str \"ok\" suffix))")
                          nil)))
        run (k/run! parent nil)]
    (is (= :completed (:status run)))
    (is (= (str "ok" suffix) (:output run)))))

(deftest generated-source-must-be-an-agent-form
  (let [run (compile-in-run "(do :side-effect (agent {:name \"child\"} [_] nil))")]
    (is (= :failed (:status run)))
    (is (= :expand (get-in run [:error :kind])))
    (is (re-find #"top-level \(agent" (get-in run [:error :message])))))

(deftest compiler-errors-are-structured
  (let [run (compile-in-run
             "(agent {:name \"bad\" :output string?} [_] (missing-symbol))")]
    (is (= :failed (:status run)))
    (is (= :evaluation (get-in run [:error :phase])))
    (is (re-find #"missing-symbol" (get-in run [:error :message])))))

(deftest generated-form-budget
  (let [parent (k/agent {:name "parent" :output string?}
                        [_]
                        (k/compile-agent!
                         "(agent {:name \"child\" :output string?} [_] \"ok\")")
                        "unreachable")
        run (k/run! parent nil {:limits {:generated-forms 0}})]
    (is (= :failed (:status run)))
    (is (= :budget (get-in run [:error :kind])))))

(deftest evaluated-agent-retains-forms
  (let [run (compile-in-run
             "(agent {:name \"child\" :output string?} [_] \"ok\")")
        agent (:output run)]
    (is (= :completed (:status run)))
    (is (= 'agent (first (k/definition agent))))
    (is (= 'karcarthy.core/make-agent
           (first (k/expansion agent))))))

(deftest agent-is-its-own-homoiconic-bridge
  (let [calls (atom 0)
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
                 "(agent {:name \"answer\" :input string? :output int?} [_] 42)"
                 :input "Return 42."}}]}
             {:type :final
              :output (get-in request [:messages 0 :content])})))
        parent
        (k/agent {:name "architect"
                  :model {:id "fake" :transport model}
                  :instructions "Write and run an Agent."
                  :output int?})
        run (k/run! parent nil)]
    (is (= :completed (:status run)))
    (is (= 42 (:output run)))
    (is (= 1 (get-in run [:usage :generated-forms])))
    (is (= ["architect" "answer"]
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

(deftest generated-program-can-reference-an-advertised-agent
  (let [helper (k/agent {:name "local-helper"
                         :description "Add punctuation to text."
                         :input string?
                         :output string?}
                        [input] (str input "!"))
        calls (atom 0)
        model (k/fake-model
               (fn [request]
                 (if (= 1 (swap! calls inc))
                   {:type :tool-calls
                    :calls
                    [{:id "write_program"
                      :name "agent"
                      :input
                      {:source
                       (str "(agent {:name \"workflow\" :input string? "
                            ":output string?} [text] "
                            "(:output (run! local-helper text)))")
                       :input "hello"}}]}
                   {:type :final
                    :output (get-in request [:messages 0 :content])})))
        parent (k/agent {:name "architect"
                         :model {:id "fake" :transport model}
                         :instructions "Write a workflow."
                         :agents [helper]
                         :output string?})
        run (k/run! parent nil)]
    (is (= :completed (:status run)) (pr-str (:error run)))
    (is (= "hello!" (:output run)))
    (is (= ["architect" "workflow"]
           (->> (:events run)
                (filter #(= :agent/started (:type %)))
                (mapv :agent))))))

(deftest generated-model-agent-starts-with-only-explicit-input
  (let [parent-calls (atom 0)
        child-messages (atom nil)
        parent-model
        (k/fake-model
         (fn [request]
           (if (= 1 (swap! parent-calls inc))
             {:type :tool-calls
              :calls
              [{:id "fresh_agent"
                :name "agent"
                :input
                {:source
                 (str "(agent {:name \"fresh\" "
                      ":model {:transport :fresh :id \"fresh\"} "
                      ":instructions \"Answer only from your input.\" "
                      ":input string? :output string?})")
                 :input "only this"}}]}
             {:type :final
              :output (get-in request [:messages 0 :content])})))
        fresh-model
        (k/fake-model
         (fn [request]
           (reset! child-messages (:messages request))
           {:type :final :output "fresh answer"}))
        session (k/memory-session
                 {:items [{:role :user :content "old question"}
                          {:role :assistant :content "old answer"}]})
        parent (k/agent {:name "parent"
                         :model {:transport :parent :id "parent"}
                         :instructions "Create a fresh specialist."
                         :output string?})
        run (k/run! parent "current question"
                    {:session session
                     :model-transports {:parent parent-model
                                        :fresh fresh-model}})]
    (is (= :completed (:status run)) (pr-str (:error run)))
    (is (= "fresh answer" (:output run)))
    (is (= [{:role :user :content "only this"}]
           @child-messages))))

(deftest generated-model-agent-can-generate-another-agent
  (let [parent-turn (atom 0)
        child-turn (atom 0)
        parent-model
        (k/fake-model
         (fn [request]
           (if (= 1 (swap! parent-turn inc))
             {:type :tool-calls
              :calls
              [{:id "child"
                :name "agent"
                :input
                {:source
                 (str "(agent {:name \"child\" "
                      ":model {:transport :child :id \"child\"} "
                      ":instructions \"Create an Agent that returns 42.\" "
                      ":input string? :output int?})")
                 :input "Return 42"}}]}
             {:type :final
              :output (get-in request [:messages 0 :content])})))
        child-model
        (k/fake-model
         (fn [request]
           (if (= 1 (swap! child-turn inc))
             {:type :tool-calls
              :calls
              [{:id "grandchild"
                :name "agent"
                :input
                {:source
                 "(agent {:name \"answer\" :input string? :output int?} [_] 42)"
                 :input "Return 42"}}]}
             {:type :final
              :output (get-in request [:messages 0 :content])})))
        parent (k/agent {:name "parent"
                         :model {:transport :parent :id "parent"}
                         :instructions "Create an Agent."
                         :output int?})
        run (k/run! parent "solve"
                    {:model-transports {:parent parent-model
                                        :child child-model}})]
    (is (= :completed (:status run)) (pr-str (:error run)))
    (is (= 42 (:output run)))
    (is (= 2 (get-in run [:usage :generated-forms])))
    (is (= ["parent" "child" "answer"]
           (->> (:events run)
                (filter #(= :agent/started (:type %)))
                (mapv :agent))))))

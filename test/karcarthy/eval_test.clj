(ns karcarthy.eval-test
  (:refer-clojure :exclude [run!])
  (:require [clojure.test :refer [deftest is]]
            [karcarthy :as k]))

(def suffix " from definition namespace")

(defn scripted-model [& responses]
  (let [remaining (atom responses)]
    (k/mock-model
     (fn [_]
       (let [response (first @remaining)]
         (swap! remaining next)
         response)))))

(defn compile-in-run [source & [limits]]
  (let [compiled (atom nil)
        compile-tool
        (k/tool {:name "compile-agent"
                 :description "Compile one Agent form."
                 :input map?
                 :output string?}
                [{:keys [source]}]
                (reset! compiled (k/compile-agent! source))
                "compiled")
        model (scripted-model
               {:type :tool-calls
                :calls [{:id "compile"
                         :name "compile-agent"
                         :input {:source source}}]}
               {:type :final :output "done"})
        compiler
        (k/agent {:name "compiler"
                  :model {:id "fake" :transport model}
                  :instructions "Compile the supplied Agent."
                  :tools [compile-tool]
                  :output string?})
        run (k/run! compiler source {:limits limits})]
    {:run run :agent @compiled}))

(defn generated-run [source input model-transports & [limits]]
  (let [turn (atom 0)
        parent-model
        (k/mock-model
         (fn [request]
           (if (= 1 (swap! turn inc))
             {:type :tool-calls
              :calls [{:id "generated"
                       :name "agent"
                       :input {:source source :input input}}]}
             {:type :final
              :output (get-in request [:messages 0 :content])})))
        parent
        (k/agent {:name "parent"
                  :model {:transport :parent :id "parent"}
                  :instructions "Create the requested Agent."
                  :output any?})]
    (k/run! parent "create an Agent"
            {:limits limits
             :model-transports (assoc model-transports :parent parent-model)})))

(deftest reads-one-form-with-reader-eval-disabled
  (is (= '(+ 1 2) (k/read-agent-form "(+ 1 2)")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"exactly one"
                        (k/read-agent-form "1 2")))
  (is (thrown? Throwable
               (k/read-agent-form "#=(System/exit 1)"))))

(deftest compiles-and-runs-generated-agent
  (let [source
        (str "(agent {:name \"child\" "
             ":model {:transport :child :id \"child\"} "
             ":instructions \"Append punctuation.\" "
             ":input string? :output string?})")
        child-model
        (k/mock-model
         (fn [request]
           {:type :final
            :output (str (get-in request [:messages 0 :content]) "!")}))
        run (generated-run source "hello" {:child child-model})]
    (is (= :completed (:status run)) (pr-str (:error run)))
    (is (= "hello!" (:output run)))
    (is (= 1 (get-in run [:usage :agent-forms])))
    (is (= ["parent" "child"]
           (->> (:events run)
                (filter #(= :agent/started (:type %)))
                (mapv :agent))))
    (is (= #{:program/read :program/expanded :program/checked
             :program/evaluated}
           (->> (:events run)
                (map :type)
                (filter #(= "program" (namespace %)))
                set)))))

(deftest generated-code-resolves-definition-namespace
  (let [{:keys [run agent]}
        (compile-in-run
         (str "(agent {:name \"child\" "
              ":model {:transport :child :id \"child\"} "
              ":instructions (str \"answer\" suffix)})"))]
    (is (= :completed (:status run)) (pr-str (:error run)))
    (is (= (str "answer" suffix)
           (get-in agent [:config :instructions])))))

(deftest generated-source-must-be-one-model-agent-form
  (let [{do-run :run}
        (compile-in-run
         "(do :side-effect (agent {:name \"child\"}))")
        {body-run :run}
        (compile-in-run
         (str "(agent {:name \"child\" "
              ":model {:transport :child :id \"child\"} "
              ":instructions \"answer\"} [_] \"ok\")"))]
    (doseq [run [do-run body-run]]
      (is (= :failed (:status run)))
      (is (= :expand (get-in run [:error :kind])))
      (is (re-find #"top-level \(agent config"
                   (get-in run [:error :message]))))))

(deftest compiler-errors-are-structured
  (let [{:keys [run]} (compile-in-run "(agent (missing-symbol))")]
    (is (= :failed (:status run)))
    (is (= :evaluation (get-in run [:error :phase])))
    (is (re-find #"missing-symbol" (get-in run [:error :message])))))

(deftest agent-form-budget
  (let [{:keys [run]}
        (compile-in-run
         (str "(agent {:name \"child\" "
              ":model {:transport :child :id \"child\"} "
              ":instructions \"answer\"})")
         {:agent-forms 0})]
    (is (= :failed (:status run)))
    (is (= :budget (get-in run [:error :kind])))))

(deftest evaluated-agent-retains-forms
  (let [{:keys [run agent]}
        (compile-in-run
         (str "(agent {:name \"child\" "
              ":model {:transport :child :id \"child\"} "
              ":instructions \"answer\"})"))]
    (is (= :completed (:status run)))
    (is (= 'agent (first (k/definition agent))))
    (is (= 'karcarthy.core/make-agent
           (first (k/expansion agent))))))

(deftest generated-agent-can-reference-an-advertised-agent
  (let [helper
        (k/agent {:name "local-helper"
                  :description "Add punctuation to text."
                  :model {:transport :helper :id "helper"}
                  :instructions "Add punctuation."
                  :input string?
                  :output string?})
        parent-turn (atom 0)
        workflow-turn (atom 0)
        parent-model
        (k/mock-model
         (fn [request]
           (if (= 1 (swap! parent-turn inc))
             {:type :tool-calls
              :calls
              [{:id "workflow"
                :name "agent"
                :input
                {:source
                 (str "(agent {:name \"workflow\" "
                      ":model {:transport :workflow :id \"workflow\"} "
                      ":instructions \"Call the helper.\" "
                      ":agents [local-helper] "
                      ":input string? :output string?})")
                 :input "hello"}}]}
             {:type :final
              :output (get-in request [:messages 0 :content])})))
        workflow-model
        (k/mock-model
         (fn [request]
           (if (= 1 (swap! workflow-turn inc))
             {:type :tool-calls
              :calls [{:id "helper"
                       :name "local-helper"
                       :input {:input "hello"}}]}
             {:type :final
              :output (get-in request [:messages 0 :content])})))
        helper-model
        (k/mock-model
         (fn [request]
           {:type :final
            :output (str (get-in request [:messages 0 :content]) "!")}))
        parent
        (k/agent {:name "architect"
                  :model {:transport :parent :id "parent"}
                  :instructions "Create a workflow."
                  :agents [helper]
                  :output string?})
        run (k/run! parent nil
                    {:model-transports {:parent parent-model
                                        :workflow workflow-model
                                        :helper helper-model}})]
    (is (= :completed (:status run)) (pr-str (:error run)))
    (is (= "hello!" (:output run)))
    (is (= ["architect" "workflow" "local-helper"]
           (->> (:events run)
                (filter #(= :agent/started (:type %)))
                (mapv :agent))))))

(deftest generated-model-agent-starts-with-only-explicit-input
  (let [parent-calls (atom 0)
        child-messages (atom nil)
        parent-model
        (k/mock-model
         (fn [request]
           (if (= 1 (swap! parent-calls inc))
             {:type :tool-calls
              :calls
              [{:id "fresh-agent"
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
        (k/mock-model
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
        (k/mock-model
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
        (k/mock-model
         (fn [request]
           (if (= 1 (swap! child-turn inc))
             {:type :tool-calls
              :calls
              [{:id "answer"
                :name "agent"
                :input
                {:source
                 (str "(agent {:name \"answer\" "
                      ":model {:transport :answer :id \"answer\"} "
                      ":instructions \"Return 42.\" "
                      ":input string? :output int?})")
                 :input "Return 42"}}]}
             {:type :final
              :output (get-in request [:messages 0 :content])})))
        answer-model (scripted-model 42)
        parent
        (k/agent {:name "parent"
                  :model {:transport :parent :id "parent"}
                  :instructions "Create an Agent."
                  :output int?})
        run (k/run! parent "solve"
                    {:model-transports {:parent parent-model
                                        :child child-model
                                        :answer answer-model}})]
    (is (= :completed (:status run)) (pr-str (:error run)))
    (is (= 42 (:output run)))
    (is (= 2 (get-in run [:usage :agent-forms])))
    (is (= ["parent" "child" "answer"]
           (->> (:events run)
                (filter #(= :agent/started (:type %)))
                (mapv :agent))))))

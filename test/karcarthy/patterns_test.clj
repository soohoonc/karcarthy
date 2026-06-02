(ns karcarthy.patterns-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [karcarthy.core :as k]
            [karcarthy.orchestrate :as o]
            [karcarthy.patterns :as p]))

(def ^:private a (k/agent "a" "base role" :model "m" :runner :r))
(def ^:private b (k/agent "b" "second role"))
(def ^:private c (k/agent "c" "third role"))

(deftest task-agent-extends-agent-as-data
  (let [tasked (p/task-agent a "Find the risks."
                             :id :risk
                             :expected-output "Three bullets.")]
    (is (= "a/risk" (:name tasked)))
    (is (= "m" (:model tasked)))
    (is (= :r (:runner tasked)))
    (is (str/includes? (:instructions tasked) "base role"))
    (is (str/includes? (:instructions tasked) "Task:\nFind the risks."))
    (is (str/includes? (:instructions tasked) "Expected output:\nThree bullets."))))

(deftest crew-compiles-tasks-to-process
  (let [tasks [{:agent a :id :one :description "First task."}
               {:agent b :id :two :description "Second task."}]]
    (testing "sequential crews become pipes"
      (let [flow (p/crew tasks)]
        (is (= :pipe (:karcarthy/type flow)))
        (is (= ["a/one" "b/two"] (mapv :name (:steps flow))))))
    (testing "concurrent crews become map nodes"
      (let [flow (p/crew tasks :process :parallel :max-concurrency 2)]
        (is (= :map (:karcarthy/type flow)))
        (is (= 2 (:max-concurrency flow)))
        (is (= ["a/one" "b/two"] (mapv :name (:branches flow))))))
    (testing "hierarchical crews become planner/worker map nodes"
      (let [manager (k/agent "manager" "plan")
            flow    (p/crew tasks :process :hierarchical :manager manager)]
        (is (= :map (:karcarthy/type flow)))
        (is (= "manager" (:name (:planner flow))))
        (is (= :pipe (:karcarthy/type (:worker flow))))))))

(deftest group-chat-compiles-round-robin-pipe
  (let [flow (p/group-chat [a b c] :rounds 5)]
    (is (= :pipe (:karcarthy/type flow)))
    (is (= ["a" "b" "c" "a" "b"] (mapv :name (:steps flow))))))

(deftest workflow-agent-compiles-deterministic-patterns
  (is (= :pipe (:karcarthy/type (p/workflow-agent :sequential [a b]))))
  (is (= :map (:karcarthy/type (p/workflow-agent :parallel [a b]))))
  (let [loop-flow (p/workflow-agent :loop [a b] :max-rounds 4)]
    (is (= :iterate (:karcarthy/type loop-flow)))
    (is (= 4 (:max-rounds loop-flow)))))

(deftest handoff-router-is-bind-data
  (let [router (k/agent "triage" "classify")
        flow   (p/handoff-router router {"billing" a} :default b)]
    (is (= :bind (:karcarthy/type flow)))
    (is (= router (:source flow)))
    (is (= a (get-in flow [:routes "billing"])))
    (is (= b (:default flow)))))

(deftest state-graph-runs-function-and-workflow-nodes
  (let [classify (fn [_state] {:text "label only" :route :billing})
        flow     (p/state-graph
                  {:start :classify
                   :nodes {:classify classify
                           :billing  {:workflow a :prompt :input}}
                   :edges {:classify {:billing :billing}
                           :billing  :end}})
        r        (o/run (k/mock-runner) flow "refund please")]
    (is (k/ok? r))
    (is (= [:classify :billing] (:path r)))
    (is (= "[a] refund please" (:text r)))
    (is (= :billing (get-in r [:state :route])))
    (is (= "label only" (get-in r [:state :outputs :classify])))))

(deftest state-graph-reports-unknown-node
  (let [flow (p/state-graph
              {:start :missing
               :nodes {}
               :edges {}})
        r    (o/run (k/mock-runner) flow "x")]
    (is (not (k/ok? r)))
    (is (= :unknown-state-graph-node (:error r)))))

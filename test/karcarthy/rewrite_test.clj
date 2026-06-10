(ns karcarthy.rewrite-test
  (:require [clojure.test :refer [deftest is testing]]
            [karcarthy.core :as k]
            [karcarthy.orchestrate :as o]
            [karcarthy.rewrite :as rw]))

(def ^:private researcher
  (k/agent {:name "researcher" :instructions "Research carefully."}))

(def ^:private writer
  (k/agent {:name "writer" :instructions "Write plainly."}))

(def ^:private reviewer
  (k/agent {:name "reviewer" :instructions "Check the answer."}))

(deftest agents-finds-leaf-agents
  (testing "agents returns valid agent leaves in traversal order"
    (let [workflow (o/pipe researcher (o/branch [writer reviewer]))]
      (is (= ["researcher" "writer" "reviewer"]
             (map :name (rw/agents workflow)))))))

(deftest over-transforms-selected-values
  (testing "over rewrites the values selected by a predicate"
    (let [workflow  (o/pipe researcher (o/branch [writer reviewer]))
          rewritten (rw/over k/agent? #(assoc % :model "claude-sonnet-4") workflow)]
      (is (o/workflow? rewritten))
      (is (= ["claude-sonnet-4" "claude-sonnet-4" "claude-sonnet-4"]
             (map :model (rw/agents rewritten)))))))

(deftest configure-stamps-agent-runtime-configuration
  (testing "configure applies common runtime settings in one pass"
    (let [workflow  (o/pipe researcher (o/branch [writer reviewer]))
          rewritten (rw/configure {:runner :claude
                                   :model "claude-sonnet-4"
                                   :instructions/suffix "State assumptions before final answer."}
                                  workflow)]
      (is (o/workflow? rewritten))
      (is (= [:claude :claude :claude]
             (map :runner (rw/agents rewritten))))
      (is (= ["claude-sonnet-4" "claude-sonnet-4" "claude-sonnet-4"]
             (map :model (rw/agents rewritten))))
      (is (= ["Research carefully.\n\nState assumptions before final answer."
              "Write plainly.\n\nState assumptions before final answer."
              "Check the answer.\n\nState assumptions before final answer."]
             (map :instructions (rw/agents rewritten)))))))

(deftest rewrites-fail-before-execution-when-invalid
  (testing "a bad over rewrite is rejected immediately"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"over result expects workflow data"
         (rw/over k/agent? #(dissoc % :name) researcher))))
  (testing "over input must already be workflow data"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"over expects workflow data"
         (rw/over k/agent? identity {:not :a-workflow}))))
  (testing "configure input must already be workflow data"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"expects workflow data"
         (rw/configure {:runner :claude} {:not :a-workflow}))))
  (testing "configure rejects invalid values clearly"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"configure :runner expects a keyword"
         (rw/configure {:runner "claude"} researcher)))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"configure :model expects a string"
         (rw/configure {:model :sonnet} researcher)))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"configure :instructions/suffix expects a string"
         (rw/configure {:instructions/suffix nil} researcher))))
  (testing "configure rejects unknown keys"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"configure contains unknown keys"
         (rw/configure {:temperature 0.2} researcher)))))

(ns karcarthy.rewrite-test
  (:require [clojure.test :refer [deftest is testing]]
            [karcarthy.core :as k]
            [karcarthy.orchestrate :as o]
            [karcarthy.rewrite :as rw]))

(def ^:private researcher (k/agent "researcher" "Research carefully."))
(def ^:private writer (k/agent "writer" "Write plainly."))
(def ^:private reviewer (k/agent "reviewer" "Check the answer."))

(deftest agents-finds-leaf-agents
  (testing "agents returns valid agent leaves in traversal order"
    (let [workflow (o/pipe researcher (o/map [writer reviewer]))]
      (is (= ["researcher" "writer" "reviewer"]
             (map :name (rw/agents workflow)))))))

(deftest over-transforms-selected-values
  (testing "over rewrites the values selected by a predicate"
    (let [workflow  (o/pipe researcher (o/map [writer reviewer]))
          rewritten (rw/over k/agent? #(assoc % :model "claude-sonnet-4") workflow)]
      (is (o/workflow? rewritten))
      (is (= ["claude-sonnet-4" "claude-sonnet-4" "claude-sonnet-4"]
             (map :model (rw/agents rewritten)))))))

(deftest config-stamps-agent-runtime-config
  (testing "config applies common runtime settings in one pass"
    (let [workflow  (o/pipe researcher (o/map [writer reviewer]))
          rewritten (rw/config {:adapter :claude
                                :model "claude-sonnet-4"
                                :instructions/suffix "State assumptions before final answer."}
                               workflow)]
      (is (o/workflow? rewritten))
      (is (= [:claude :claude :claude]
             (map :adapter (rw/agents rewritten))))
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
  (testing "config input must already be workflow data"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"expects workflow data"
         (rw/config {:adapter :claude} {:not :a-workflow}))))
  (testing "config rejects invalid values clearly"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"config :adapter expects a keyword"
         (rw/config {:adapter "claude"} researcher)))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"config :model expects a string"
         (rw/config {:model :sonnet} researcher)))
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"config :instructions/suffix expects a string"
         (rw/config {:instructions/suffix nil} researcher))))
  (testing "config rejects unknown keys"
    (is (thrown-with-msg?
         clojure.lang.ExceptionInfo
         #"config contains unknown keys"
         (rw/config {:temperature 0.2} researcher)))))

(ns karcarthy-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [karcarthy :as kc]))

(deftest facade-reexports-fns
  (testing "core + orchestrate functions are reachable under one alias"
    (let [a (kc/agent "x" "i")]
      (is (kc/agent? a)))
    (let [s (kc/subagent "reviewer" "Use for review." "Review carefully.")]
      (is (kc/subagent? s)))
    (let [r (kc/run (kc/mock-runner)
                    (kc/pipe (kc/agent "a" "i") (kc/agent "b" "i"))
                    "hi")]
      (is (kc/ok? r))
      (is (= "[b] [a] hi" (:text r))))
    (is (kc/ok? (kc/run (kc/fn-runner identity)
                         (kc/agent "fn" "i")
                         "hi")))
    (is (= "[a] HI" (:text (kc/run (kc/mock-runner)
                                    (kc/pipe (kc/step str/upper-case)
                                             (kc/agent "a" "i"))
                                    "hi"))))
    (is (kc/ok? (kc/run (kc/process-runner "cat")
                         (kc/agent "process" "i")
                         "hi")))
    (let [a (kc/agent "a" "i")
          b (kc/agent "b" "i")]
      (is (kc/workflow? (kc/branch [a b])))
      (is (kc/workflow? (kc/route a {:next b})))
      (is (kc/workflow? (kc/continue a b)))
      (is (kc/workflow? (kc/dynamic a))))))

(deftest facade-reexports-macros
  (testing "defagent and defworkflow are re-exported as working macros"
    (kc/defagent facade-agent "instr" :model "m")
    (is (= "facade-agent" (:name facade-agent)))
    (is (= "m" (:model facade-agent)))
    (kc/defsubagent facade-subagent "Use for review." "Review carefully.")
    (is (= "facade-subagent" (:name facade-subagent)))
    (kc/defworkflow facade-workflow (kc/pipe facade-agent))
    (is (kc/workflow? facade-workflow))))

(deftest facade-reexports-rewrites
  (testing "workflow rewrites are reachable under the facade"
    (let [workflow  (kc/pipe (kc/agent "a" "i") (kc/agent "b" "j"))
          rewritten (kc/configure {:runner :mock :model "m"} workflow)]
      (is (kc/workflow? rewritten))
      (is (= ["a" "b"] (map :name (kc/agents rewritten))))
      (is (= [:mock :mock] (map :runner (kc/agents rewritten))))
      (is (= ["m" "m"] (map :model (kc/agents rewritten))))
      (is (= ["x" "x"]
             (map :instructions
                  (kc/agents
                   (kc/over kc/agent?
                            (fn [agent] (assoc agent :instructions "x"))
                            workflow))))))))

(deftest facade-reexports-values
  (is (string? kc/dsl-reference))
  (is (string? (kc/explain-agent {:karcarthy/type :agent})))
  (is (string? (kc/explain-subagent {:karcarthy/type :subagent})))
  (is (map? kc/edn-schema))
  (is (map? kc/json-schema))
  (is (kc/agent? (kc/read-agent "{:karcarthy/type :agent :name \"x\" :instructions \"i\"}"))))

(deftest facade-reexports-dynamic-workflow-helpers
  (testing "the one-alias surface gets workflow builders, not the stepping API"
    (is (kc/workflow? (kc/dynamic (kc/agent "workflow" "emit EDN ops"))))
    (is (map? (kc/agent-ref "worker")))
    (is (map? (kc/workflow-ref "draft")))
    (is (nil? (ns-resolve 'karcarthy 'step!)))
    (is (nil? (ns-resolve 'karcarthy 'state)))
    (is (nil? (ns-resolve 'karcarthy 'text->op)))
    (is (nil? (ns-resolve 'karcarthy 'dynamic-reference)))))

(deftest facade-hides-low-level-execution-apis
  (testing "normal users get one execution entrypoint: run"
    (is (nil? (ns-resolve 'karcarthy 'run-agent)))
    (is (nil? (ns-resolve 'karcarthy 'Runner)))
    (is (nil? (ns-resolve 'karcarthy 'resolve-runner)))
    (is (nil? (ns-resolve 'karcarthy 'evolve)))
    (is (nil? (ns-resolve 'karcarthy 'map)))
    (is (nil? (ns-resolve 'karcarthy 'bind)))
    (is (nil? (ns-resolve 'karcarthy 'iterate)))
    (is (nil? (ns-resolve 'karcarthy 'config)))
    (is (nil? (ns-resolve 'karcarthy 'shell-runner)))
    (is (nil? (ns-resolve 'karcarthy '-run)))))

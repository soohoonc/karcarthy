(ns karcarthy-test
  (:require [clojure.test :refer [deftest is testing]]
            [karcarthy :as kc]))

(deftest facade-reexports-fns
  (testing "core + orchestrate functions are reachable under one alias"
    (let [a (kc/agent "x" "i")]
      (is (kc/agent? a)))
    (let [r (kc/run (kc/mock-adapter)
                    (kc/pipe (kc/agent "a" "i") (kc/agent "b" "i"))
                    "hi")]
      (is (kc/ok? r))
      (is (= "[b] [a] hi" (:text r))))))

(deftest facade-reexports-macros
  (testing "defagent and defworkflow are re-exported as working macros"
    (kc/defagent facade-agent "instr" :model "m")
    (is (= "facade-agent" (:name facade-agent)))
    (is (= "m" (:model facade-agent)))
    (kc/defworkflow facade-workflow (kc/pipe facade-agent))
    (is (kc/workflow? facade-workflow))))

(deftest facade-reexports-rewrites
  (testing "workflow rewrites are reachable under the facade"
    (let [workflow  (kc/pipe (kc/agent "a" "i") (kc/agent "b" "j"))
          rewritten (kc/config {:adapter :mock :model "m"} workflow)]
      (is (kc/workflow? rewritten))
      (is (= ["a" "b"] (map :name (kc/agents rewritten))))
      (is (= [:mock :mock] (map :adapter (kc/agents rewritten))))
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
  (is (map? kc/edn-schema))
  (is (map? kc/json-schema))
  (is (kc/agent? (kc/read-agent "{:karcarthy/type :agent :name \"x\" :instructions \"i\"}"))))

(deftest facade-hides-low-level-execution-apis
  (testing "normal users get one execution entrypoint: run"
    (is (nil? (ns-resolve 'karcarthy 'run-agent)))
    (is (nil? (ns-resolve 'karcarthy 'Adapter)))
    (is (nil? (ns-resolve 'karcarthy 'resolve-adapter)))
    (is (nil? (ns-resolve 'karcarthy 'evolve)))
    (is (nil? (ns-resolve 'karcarthy '-run)))))

(ns karcarthy.orchestrate-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [karcarthy.core :as k]
            [karcarthy.orchestrate :as o]))

;; The default mock harness replies "[<agent-name>] <input>", which makes
;; threading and routing deterministic to assert.
(def ^:private h (k/mock-harness))

(def ^:private a (k/agent "a" "i"))
(def ^:private b (k/agent "b" "i"))
(def ^:private c (k/agent "c" "i"))

;; A harness whose named agents fail, to exercise short-circuiting.
(defn- failing-harness [fail-names]
  (reify k/Harness
    (-run [_ agent prompt _]
      (k/result {:agent (:name agent)
                 :ok?   (not (contains? fail-names (:name agent)))
                 :text  (str "[" (:name agent) "] " prompt)}))))

(deftest constructors-build-data
  (testing "flows are plain tagged data"
    (is (= {:karcarthy/type :chain :steps [a b]} (o/chain a b)))
    (is (= {:karcarthy/type :parallel :branches [a b]} (o/parallel a b)))
    (is (= {:karcarthy/type :route :router :r :routes {}} (o/route :r {})))
    (is (= c (get-in (o/route :r {} :default c) [:default])))))

(deftest chain-threads-text
  (testing "each result's text feeds the next step"
    (let [r (o/run-flow h (o/chain a b c) "hi")]
      (is (k/ok? r))
      ;; a:"[a] hi" -> b:"[b] [a] hi" -> c:"[c] [b] [a] hi"
      (is (= "[c] [b] [a] hi" (:text r))))))

(deftest chain-short-circuits-on-failure
  (testing "a failing step stops the chain and is returned"
    (let [r (o/run-flow (failing-harness #{"b"}) (o/chain a b c) "hi")]
      (is (not (k/ok? r)))
      (is (= "b" (:agent r)))
      ;; c never ran, so its tag is absent from the text
      (is (not (str/includes? (:text r) "[c]"))))))

(deftest parallel-runs-branches
  (testing "branches run on the same input; results gathered in order"
    (let [r (o/run-flow h (o/parallel a b c) "hi")]
      (is (k/ok? r))
      (is (= 3 (count (:results r))))
      (is (= ["[a] hi" "[b] hi" "[c] hi"] (map :text (:results r))))
      (is (= "[a] hi\n\n[b] hi\n\n[c] hi" (:text r))))))

(deftest parallel-ok-requires-all-branches
  (testing "one failing branch makes the whole node not-ok"
    (let [r (o/run-flow (failing-harness #{"b"}) (o/parallel a b c) "hi")]
      (is (not (k/ok? r)))
      (is (= 3 (count (:results r)))))))

(deftest parallel-with-gather
  (testing "a gather fn post-processes the branch results"
    (let [flow (o/parallel* [a b c]
                            :gather (fn [rs] (k/result {:text (str/join "|" (map :text rs))})))
          r    (o/run-flow h flow "hi")]
      (is (= "[a] hi|[b] hi|[c] hi" (:text r)))
      (is (= 3 (count (:results r)))))))

(deftest route-with-fn-router
  (testing "a pure-fn router selects a branch by label"
    (let [router (fn [in] (if (even? (count in)) :even :odd))
          flow   (o/route router {:even a :odd b})]
      (is (= "[a] hi"  (:text (o/run-flow h flow "hi"))))   ; count 2 -> :even
      (is (= "[b] yo!" (:text (o/run-flow h flow "yo!")))))))  ; count 3 -> :odd

(deftest route-with-agent-router
  (testing "an agent router's reply text is used as the label"
    (let [router  (k/agent "classifier" "classify")
          ;; harness that makes the classifier emit the label "billing"
          rh      (reify k/Harness
                    (-run [_ agent prompt _]
                      (k/result {:agent (:name agent)
                                 :text  (if (= "classifier" (:name agent))
                                          "billing"
                                          (str "[" (:name agent) "] " prompt))})))
          flow    (o/route router {"billing" a "support" b})]
      (is (= "[a] refund please" (:text (o/run-flow rh flow "refund please")))))))

(deftest route-no-match
  (testing "an unmatched label yields a not-ok :no-route result"
    (let [flow (o/route (fn [_] :nope) {:yes a})
          r    (o/run-flow h flow "hi")]
      (is (not (k/ok? r)))
      (is (= :no-route (:error r)))
      (is (= :nope (:label r))))))

(deftest route-default
  (testing ":default is used when no label matches"
    (let [flow (o/route (fn [_] :nope) {:yes a} :default b)
          r    (o/run-flow h flow "hi")]
      (is (k/ok? r))
      (is (= "[b] hi" (:text r))))))

(deftest nested-composition
  (testing "flows nest: a chain whose step is a parallel"
    (let [flow (o/chain a (o/parallel b c))
          r    (o/run-flow h flow "hi")]
      ;; a:"[a] hi"; then parallel feeds that to b and c
      (is (= ["[b] [a] hi" "[c] [a] hi"] (map :text (:results r)))))))

(deftest unknown-node-throws
  (is (thrown? clojure.lang.ExceptionInfo
               (o/run-flow h {:karcarthy/type :nonsense} "hi"))))

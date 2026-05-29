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

;; A harness that *throws* for named agents, to exercise fault isolation.
(defn- throwing-harness [throw-names]
  (reify k/Harness
    (-run [_ agent prompt _]
      (if (contains? throw-names (:name agent))
        (throw (ex-info "boom" {:agent (:name agent)}))
        (k/result {:agent (:name agent)
                   :text  (str "[" (:name agent) "] " prompt)})))))

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

(deftest refine-accepts-immediately
  (testing "an evaluator that accepts the first draft returns it at round 1"
    (let [flow (o/refine a (fn [_draft _input] {:accept? true}))
          r    (o/run-flow h flow "topic")]
      (is (k/ok? r))
      (is (true? (:accepted? r)))
      (is (= 1 (:rounds r)))
      (is (= "[a] topic" (:text r))))))         ; first draft on the raw input

(deftest refine-loops-to-max-rounds
  (testing "an evaluator that never accepts stops at :max-rounds, not accepted"
    (let [flow (o/refine a (fn [_ _] {:accept? false :feedback "more"}) :max-rounds 3)
          r    (o/run-flow h flow "topic")]
      (is (false? (:accepted? r)))
      (is (= 3 (:rounds r)))
      (is (str/includes? (:text r) "topic")))))

(deftest refine-accepts-on-later-round
  (testing "the loop revises until the evaluator accepts"
    (let [calls   (atom 0)
          eval-fn (fn [_ _] (swap! calls inc) {:accept? (>= @calls 2) :feedback "f"})
          flow    (o/refine a eval-fn :max-rounds 5)
          r       (o/run-flow h flow "x")]
      (is (true? (:accepted? r)))
      (is (= 2 (:rounds r))))))

(deftest refine-with-agent-evaluator
  (testing "an agent evaluator's reply is the verdict (ACCEPT vs feedback)"
    (let [accept-h (reify k/Harness
                     (-run [_ ag p _]
                       (k/result {:agent (:name ag)
                                  :text  (if (= "judge" (:name ag))
                                           "ACCEPT" (str "[" (:name ag) "] " p))})))
          reject-h (reify k/Harness
                     (-run [_ ag p _]
                       (k/result {:agent (:name ag)
                                  :text  (if (= "judge" (:name ag))
                                           "needs work" (str "[" (:name ag) "] " p))})))
          judge    (k/agent "judge" "judge")]
      (let [r (o/run-flow accept-h (o/refine a judge) "x")]
        (is (true? (:accepted? r)))
        (is (= 1 (:rounds r))))
      (let [r (o/run-flow reject-h (o/refine a judge :max-rounds 2) "x")]
        (is (false? (:accepted? r)))
        (is (= 2 (:rounds r)))))))

(deftest refine-bails-on-worker-failure
  (testing "a failing worker short-circuits the refine loop"
    (let [flow (o/refine a (fn [_ _] {:accept? true}))
          r    (o/run-flow (failing-harness #{"a"}) flow "x")]
      (is (not (k/ok? r)))
      (is (= "a" (:agent r))))))

(deftest orchestrate-fn-planner
  (testing "a fn planner's subtasks are each handled by the worker"
    (let [flow (o/orchestrate (fn [in] (str/split in #",")) a)
          r    (o/run-flow h flow "x,y,z")]
      (is (k/ok? r))
      (is (= ["x" "y" "z"] (:subtasks r)))
      (is (= ["[a] x" "[a] y" "[a] z"] (map :text (:results r))))
      (is (= "[a] x\n\n[a] y\n\n[a] z" (:text r))))))

(deftest orchestrate-agent-planner-parses-list
  (testing "an agent planner's reply is parsed into subtasks (markers stripped)"
    (let [ph   (reify k/Harness
                 (-run [_ ag p _]
                   (k/result {:agent (:name ag)
                              :text  (if (= "planner" (:name ag))
                                       "- alpha\n2. beta\n  * gamma\n"
                                       (str "[" (:name ag) "] " p))})))
          flow (o/orchestrate (k/agent "planner" "plan") a)
          r    (o/run-flow ph flow "do stuff")]
      (is (= ["alpha" "beta" "gamma"] (:subtasks r)))
      (is (= ["[a] alpha" "[a] beta" "[a] gamma"] (map :text (:results r)))))))

(deftest orchestrate-synthesize
  (testing "a synthesize fn combines the worker results"
    (let [flow (o/orchestrate (fn [_] ["p" "q"]) a
                              :synthesize (fn [rs _] (k/result {:text (str/join "+" (map :text rs))})))
          r    (o/run-flow h flow "in")]
      (is (= "[a] p+[a] q" (:text r))))))

(deftest orchestrate-bounded-concurrency
  (testing "all subtasks run correctly even with a small concurrency bound"
    (let [flow (o/orchestrate (fn [_] (map str (range 5))) a :max-concurrency 2)
          r    (o/run-flow h flow "in")]
      (is (= 5 (count (:results r))))
      (is (= (mapv #(str "[a] " %) (range 5)) (mapv :text (:results r)))))))

(deftest flow-predicate
  (testing "flow? recognizes agents and known nodes, rejects the rest"
    (is (o/flow? a))
    (is (o/flow? (o/chain a b)))
    (is (o/flow? (o/orchestrate (fn [_] []) a)))
    (is (not (o/flow? {:karcarthy/type :nonsense})))
    (is (not (o/flow? {:karcarthy/type :default})))   ; :default isn't a real node
    (is (not (o/flow? 42)))))

(deftest defflow-macro
  (testing "defflow defines and validates a flow"
    (o/defflow good-flow (o/chain a b))
    (is (o/flow? good-flow))
    (is (= :chain (:karcarthy/type good-flow))))
  (testing "defflow rejects a non-flow at load time"
    ;; eval of a top-level def wraps the runtime throw in a CompilerException,
    ;; so assert our message appears somewhere in the cause chain.
    (let [ex   (try (eval '(karcarthy.orchestrate/defflow bad-flow
                             {:karcarthy/type :nonsense}))
                    (catch Throwable t t))
          msgs (->> (iterate ex-cause ex)
                    (take-while some?)
                    (map ex-message)
                    (str/join " "))]
      (is (some? ex))
      (is (str/includes? msgs "not a runnable flow")))))

;; Records each call's :resume opt and returns a per-agent session id, so we can
;; assert that handoff threads the session forward.
(defn- session-recording-harness [log]
  (reify k/Harness
    (-run [_ agent prompt opts]
      (swap! log conj {:agent (:name agent) :prompt prompt :resume (:resume opts)})
      (k/result {:agent      (:name agent)
                 :text       (str "[" (:name agent) "] " prompt)
                 :session-id (str "sess-" (:name agent))}))))

(deftest handoff-threads-session
  (testing "to inherits from's text as input and from's session as :resume"
    (let [log (atom [])
          r   (o/run-flow (session-recording-harness log) (o/handoff a b) "hi")]
      (is (k/ok? r))
      (is (= "[b] [a] hi" (:text r)))
      (is (= [{:agent "a" :prompt "hi"     :resume nil}
              {:agent "b" :prompt "[a] hi" :resume "sess-a"}]
             @log)))))

(deftest handoff-prompt-override
  (testing ":prompt overrides the handed-off input"
    (let [log (atom [])
          r   (o/run-flow (session-recording-harness log)
                          (o/handoff a b :prompt "explicit") "hi")]
      (is (= "[b] explicit" (:text r)))
      (is (= "explicit" (:prompt (second @log)))))))

(deftest handoff-bails-on-from-failure
  (testing "if the first agent fails, the handoff returns that failure"
    (let [r (o/run-flow (failing-harness #{"a"}) (o/handoff a b) "hi")]
      (is (not (k/ok? r)))
      (is (= "a" (:agent r))))))

(deftest parallel-isolates-throwing-branch
  (testing "a branch that throws becomes a not-ok result; siblings are unaffected"
    (let [r (o/run-flow (throwing-harness #{"b"}) (o/parallel a b c) "hi")]
      (is (not (k/ok? r)))
      (is (= 3 (count (:results r))))
      (is (= [true false true] (mapv k/ok? (:results r))))
      (is (= "[a] hi" (:text (first (:results r)))))
      (is (= "[c] hi" (:text (last (:results r)))))
      (is (= "boom" (:error (second (:results r))))))))

(deftest route-matches-fuzzily
  (testing "an agent router whose reply contains the label still routes"
    (let [rh   (reify k/Harness
                 (-run [_ ag p _]
                   (k/result {:agent (:name ag)
                              :text  (if (= "classifier" (:name ag))
                                       "This is clearly a BILLING question."
                                       (str "[" (:name ag) "] " p))})))
          flow (o/route (k/agent "classifier" "c") {"billing" a "support" b})]
      (is (= "[a] refund" (:text (o/run-flow rh flow "refund")))))))

(deftest route-fn-label-case-insensitive
  (testing "a string label matches a route key case-insensitively"
    (let [flow (o/route (fn [_] "BILLING") {"billing" a})]
      (is (= "[a] x" (:text (o/run-flow h flow "x")))))))

(deftest unknown-node-throws
  (is (thrown? clojure.lang.ExceptionInfo
               (o/run-flow h {:karcarthy/type :nonsense} "hi"))))

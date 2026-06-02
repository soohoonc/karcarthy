(ns karcarthy.orchestrate-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [karcarthy.core :as k]
            [karcarthy.orchestrate :as o]))

;; The default mock adapter replies "[<agent-name>] <input>", which makes
;; threading and routing deterministic to assert.
(def ^:private h (k/mock-adapter))

(def ^:private a (k/agent "a" "i"))
(def ^:private b (k/agent "b" "i"))
(def ^:private c (k/agent "c" "i"))

;; An adapter whose named agents fail, to exercise short-circuiting.
(defn- failing-adapter [fail-names]
  (reify k/Adapter
    (-run [_ agent prompt _]
      (k/result {:agent (:name agent)
                 :ok?   (not (contains? fail-names (:name agent)))
                 :text  (str "[" (:name agent) "] " prompt)}))))

;; An adapter that *throws* for named agents, to exercise fault isolation.
(defn- throwing-adapter [throw-names]
  (reify k/Adapter
    (-run [_ agent prompt _]
      (if (contains? throw-names (:name agent))
        (throw (ex-info "boom" {:agent (:name agent)}))
        (k/result {:agent (:name agent)
                   :text  (str "[" (:name agent) "] " prompt)})))))

(deftest constructors-build-data
  (testing "workflows are plain tagged data"
    (is (= {:karcarthy/type :pipe :steps [a b]} (o/pipe a b)))
    (is (= {:karcarthy/type :map :branches [a b]} (o/map [a b])))
    (is (= {:karcarthy/type :bind :source :r :routes {}} (o/bind :r {})))
    (is (= {:karcarthy/type :iterate :worker a :evaluator b :max-rounds 2}
           (o/iterate a b :max-rounds 2)))))

(deftest reduce-adds-a-combiner-to-mapped-work
  (testing "reduce attaches a reducer function to a mapped branch collection"
    (let [combine (fn [rs] (k/result {:text (str/join "|" (map :text rs))}))
          flow    (o/reduce combine (o/map [a b c]))
          r      (o/run h flow "hi")]
      (is (= "[a] hi|[b] hi|[c] hi" (:text r))))))

(deftest pipe-threads-text
  (testing "each result's text feeds the next step"
    (let [r (o/run h (o/pipe a b c) "hi")]
      (is (k/ok? r))
      ;; a:"[a] hi" -> b:"[b] [a] hi" -> c:"[c] [b] [a] hi"
      (is (= "[c] [b] [a] hi" (:text r))))))

(deftest pipe-short-circuits-on-failure
  (testing "a failing step stops the pipe and is returned"
    (let [r (o/run (failing-adapter #{"b"}) (o/pipe a b c) "hi")]
      (is (not (k/ok? r)))
      (is (= "b" (:agent r)))
      ;; c never ran, so its tag is absent from the text
      (is (not (str/includes? (:text r) "[c]"))))))

(deftest map-runs-branches
  (testing "branches run on the same input; results are returned in order"
    (let [r (o/run h (o/map [a b c]) "hi")]
      (is (k/ok? r))
      (is (= 3 (count (:results r))))
      (is (= ["[a] hi" "[b] hi" "[c] hi"] (map :text (:results r))))
      (is (= "[a] hi\n\n[b] hi\n\n[c] hi" (:text r))))))

(deftest map-ok-requires-all-branches
  (testing "one failing branch makes the whole node not-ok"
    (let [r (o/run (failing-adapter #{"b"}) (o/map [a b c]) "hi")]
      (is (not (k/ok? r)))
      (is (= 3 (count (:results r)))))))

(deftest map-with-reduce
  (testing "a reducer post-processes the branch results"
    (let [flow (o/map [a b c]
                      :reduce (fn [rs] (k/result {:text (str/join "|" (map :text rs))})))
          r    (o/run h flow "hi")]
      (is (= "[a] hi|[b] hi|[c] hi" (:text r)))
      (is (= 3 (count (:results r)))))))

(deftest bind-route-with-fn-source
  (testing "a pure-fn source selects a branch by label"
    (let [router (fn [in] (if (even? (count in)) :even :odd))
          flow   (o/bind router {:even a :odd b})]
      (is (= "[a] hi"  (:text (o/run h flow "hi"))))   ; count 2 -> :even
      (is (= "[b] yo!" (:text (o/run h flow "yo!")))))))  ; count 3 -> :odd

(deftest bind-route-with-agent-source
  (testing "an agent source's reply text is used as the label"
    (let [router  (k/agent "classifier" "classify")
          ;; adapter that makes the classifier emit the label "billing"
          rh      (reify k/Adapter
                    (-run [_ agent prompt _]
                      (k/result {:agent (:name agent)
                                 :text  (if (= "classifier" (:name agent))
                                          "billing"
                                          (str "[" (:name agent) "] " prompt))})))
          flow    (o/bind router {"billing" a "support" b})]
      (is (= "[a] refund please" (:text (o/run rh flow "refund please")))))))

(deftest bind-route-no-match
  (testing "an unmatched label yields a not-ok :no-route result"
    (let [flow (o/bind (fn [_] :nope) {:yes a})
          r    (o/run h flow "hi")]
      (is (not (k/ok? r)))
      (is (= :no-route (:error r)))
      (is (= :nope (:label r))))))

(deftest bind-route-default
  (testing ":default is used when no label matches"
    (let [flow (o/bind (fn [_] :nope) {:yes a} :default b)
          r    (o/run h flow "hi")]
      (is (k/ok? r))
      (is (= "[b] hi" (:text r))))))

(deftest nested-composition
  (testing "workflows nest: a pipe whose step is a map"
    (let [flow (o/pipe a (o/map [b c]))
          r    (o/run h flow "hi")]
      ;; a:"[a] hi"; then map feeds that to b and c
      (is (= ["[b] [a] hi" "[c] [a] hi"] (map :text (:results r)))))))

(deftest iterate-accepts-immediately
  (testing "an evaluator that accepts the first draft returns it at round 1"
    (let [flow (o/iterate a (fn [_draft _input] {:accept? true}))
          r    (o/run h flow "topic")]
      (is (k/ok? r))
      (is (true? (:accepted? r)))
      (is (= 1 (:rounds r)))
      (is (= "[a] topic" (:text r))))))         ; first draft on the raw input

(deftest iterate-loops-to-max-rounds
  (testing "an evaluator that never accepts stops at :max-rounds, not accepted"
    (let [flow (o/iterate a (fn [_ _] {:accept? false :feedback "more"}) :max-rounds 3)
          r    (o/run h flow "topic")]
      (is (false? (:accepted? r)))
      (is (= 3 (:rounds r)))
      (is (str/includes? (:text r) "topic")))))

(deftest iterate-accepts-on-later-round
  (testing "the loop revises until the evaluator accepts"
    (let [calls   (atom 0)
          eval-fn (fn [_ _] (swap! calls inc) {:accept? (>= @calls 2) :feedback "f"})
          flow    (o/iterate a eval-fn :max-rounds 5)
          r       (o/run h flow "x")]
      (is (true? (:accepted? r)))
      (is (= 2 (:rounds r))))))

(deftest iterate-with-agent-evaluator
  (testing "an agent evaluator's reply is the verdict (ACCEPT vs feedback)"
    (let [accept-h (reify k/Adapter
                     (-run [_ ag p _]
                       (k/result {:agent (:name ag)
                                  :text  (if (= "judge" (:name ag))
                                           "ACCEPT" (str "[" (:name ag) "] " p))})))
          reject-h (reify k/Adapter
                     (-run [_ ag p _]
                       (k/result {:agent (:name ag)
                                  :text  (if (= "judge" (:name ag))
                                           "needs work" (str "[" (:name ag) "] " p))})))
          judge    (k/agent "judge" "judge")]
      (let [r (o/run accept-h (o/iterate a judge) "x")]
        (is (true? (:accepted? r)))
        (is (= 1 (:rounds r))))
      (let [r (o/run reject-h (o/iterate a judge :max-rounds 2) "x")]
        (is (false? (:accepted? r)))
        (is (= 2 (:rounds r)))))))

(deftest iterate-bails-on-worker-failure
  (testing "a failing worker short-circuits the iterate loop"
    (let [flow (o/iterate a (fn [_ _] {:accept? true}))
          r    (o/run (failing-adapter #{"a"}) flow "x")]
      (is (not (k/ok? r)))
      (is (= "a" (:agent r))))))

(deftest planned-map-fn-planner
  (testing "a fn planner's subtasks are each handled by the worker"
    (let [flow (o/map (fn [in] (str/split in #",")) a)
          r    (o/run h flow "x,y,z")]
      (is (k/ok? r))
      (is (= ["x" "y" "z"] (:subtasks r)))
      (is (= ["[a] x" "[a] y" "[a] z"] (map :text (:results r))))
      (is (= "[a] x\n\n[a] y\n\n[a] z" (:text r))))))

(deftest planned-map-agent-planner-parses-list
  (testing "an agent planner's reply is parsed into subtasks (markers stripped)"
    (let [ph   (reify k/Adapter
                 (-run [_ ag p _]
                   (k/result {:agent (:name ag)
                              :text  (if (= "planner" (:name ag))
                                       "- alpha\n2. beta\n  * gamma\n"
                                       (str "[" (:name ag) "] " p))})))
          flow (o/map (k/agent "planner" "plan") a)
          r    (o/run ph flow "do stuff")]
      (is (= ["alpha" "beta" "gamma"] (:subtasks r)))
      (is (= ["[a] alpha" "[a] beta" "[a] gamma"] (map :text (:results r)))))))

(deftest planned-map-reduce
  (testing "a reducer combines the worker results"
    (let [flow (o/map (fn [_] ["p" "q"]) a
                      :reduce (fn [rs _] (k/result {:text (str/join "+" (map :text rs))})))
          r    (o/run h flow "in")]
      (is (= "[a] p+[a] q" (:text r))))))

(deftest planned-map-bounded-concurrency
  (testing "all subtasks run correctly even with a small concurrency bound"
    (let [flow (o/map (fn [_] (map str (range 5))) a :max-concurrency 2)
          r    (o/run h flow "in")]
      (is (= 5 (count (:results r))))
      (is (= (mapv #(str "[a] " %) (range 5)) (mapv :text (:results r)))))))

(deftest workflow-predicate
  (testing "workflow? recognizes agents and known nodes, rejects the rest"
    (is (o/workflow? a))
    (is (o/workflow? (o/pipe a b)))
    (is (o/workflow? (o/map (fn [_] []) a)))
    (is (not (o/workflow? {:karcarthy/type :pipe
                            :steps [{:karcarthy/type :chain :steps [a]}]})))
    (is (not (o/workflow? {:karcarthy/type :nonsense})))
    (is (not (o/workflow? {:karcarthy/type :default})))   ; :default isn't a real node
    (is (not (o/workflow? 42)))))

(deftest defworkflow-macro
  (testing "defworkflow defines and validates a workflow"
    (o/defworkflow good-workflow (o/pipe a b))
    (is (o/workflow? good-workflow))
    (is (= :pipe (:karcarthy/type good-workflow))))
  (testing "defworkflow rejects a non-workflow at load time"
    (let [ex   (try (eval '(karcarthy.orchestrate/defworkflow bad-workflow
                             {:karcarthy/type :nonsense}))
                    (catch Throwable t t))
          msgs (->> (iterate ex-cause ex)
                    (take-while some?)
                    (map ex-message)
                    (str/join " "))]
      (is (some? ex))
      (is (str/includes? msgs "not a runnable workflow")))))

;; Records each call's :resume opt and returns a per-agent session id, so we can
;; assert that bind continuation threads the session forward.
(defn- session-recording-adapter [log]
  (reify k/Adapter
    (-run [_ agent prompt opts]
      (swap! log conj {:agent (:name agent) :prompt prompt :resume (:resume opts)})
      (k/result {:agent      (:name agent)
                 :text       (str "[" (:name agent) "] " prompt)
                 :session-id (str "sess-" (:name agent))}))))

(deftest bind-continuation-threads-session
  (testing "to inherits source text as input and source session as :resume"
    (let [log (atom [])
          r   (o/run (session-recording-adapter log) (o/bind a b) "hi")]
      (is (k/ok? r))
      (is (= "[b] [a] hi" (:text r)))
      (is (= [{:agent "a" :prompt "hi"     :resume nil}
              {:agent "b" :prompt "[a] hi" :resume "sess-a"}]
             @log)))))

(deftest bind-continuation-prompt-override
  (testing ":prompt overrides the handed-off input"
    (let [log (atom [])
          r   (o/run (session-recording-adapter log)
                     (o/bind a b :prompt "explicit") "hi")]
      (is (= "[b] explicit" (:text r)))
      (is (= "explicit" (:prompt (second @log)))))))

(deftest bind-continuation-bails-on-source-failure
  (testing "if the source agent fails, bind returns that failure"
    (let [r (o/run (failing-adapter #{"a"}) (o/bind a b) "hi")]
      (is (not (k/ok? r)))
      (is (= "a" (:agent r))))))

(deftest map-isolates-throwing-branch
  (testing "a branch that throws becomes a not-ok result; siblings are unaffected"
    (let [r (o/run (throwing-adapter #{"b"}) (o/map [a b c]) "hi")]
      (is (not (k/ok? r)))
      (is (= 3 (count (:results r))))
      (is (= [true false true] (mapv k/ok? (:results r))))
      (is (= "[a] hi" (:text (first (:results r)))))
      (is (= "[c] hi" (:text (last (:results r)))))
      (is (= "boom" (:error (second (:results r))))))))

(deftest bind-route-matches-fuzzily
  (testing "an agent router whose reply contains the label still routes"
    (let [rh   (reify k/Adapter
                 (-run [_ ag p _]
                   (k/result {:agent (:name ag)
                              :text  (if (= "classifier" (:name ag))
                                       "This is clearly a BILLING question."
                                       (str "[" (:name ag) "] " p))})))
          flow (o/bind (k/agent "classifier" "c") {"billing" a "support" b})]
      (is (= "[a] refund" (:text (o/run rh flow "refund")))))))

(deftest bind-route-fn-label-case-insensitive
  (testing "a string label matches a route key case-insensitively"
    (let [flow (o/bind (fn [_] "BILLING") {"billing" a})]
      (is (= "[a] x" (:text (o/run h flow "x")))))))

(deftest workflow-over-adapter-registry
  (testing "a registry threads through orchestration; each agent uses its adapter"
    (let [reg  {:up      (k/mock-adapter (fn [{:keys [prompt]}] (str/upper-case prompt)))
                :default (k/mock-adapter (fn [{:keys [prompt]}] prompt))}
          flow (o/pipe (k/agent "shout" "i" :adapter :up) (k/agent "echo" "i"))]
      ;; shout (:up) upper-cases "hi" -> "HI"; echo (:default) passes it through
      (is (= "HI" (:text (o/run reg flow "hi")))))))

(deftest unknown-node-throws
  (is (thrown? clojure.lang.ExceptionInfo
               (o/run h {:karcarthy/type :nonsense} "hi"))))

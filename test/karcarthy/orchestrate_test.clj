(ns karcarthy.orchestrate-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [karcarthy.core :as k]
            [karcarthy.orchestrate :as o]))

;; The default mock adapter replies "[<agent-name>] <input>", which makes
;; threading and routing deterministic to assert.
(def ^:private h (k/mock-adapter))

(def ^:private a (k/agent "a" "i"))
(def ^:private b (k/agent "b" "i"))
(def ^:private c (k/agent "c" "i"))
(def ^:private planner (k/agent "planner" "reply with {:subtasks [...] }"))
(def ^:private judge (k/agent "judge" "reply with {:accept? ...}"))
(def ^:private router (k/agent "router" "reply with {:route ...}"))
(def ^:private reducer (k/agent "reducer" "reduce mapped EDN results"))

(defn- scripted-adapter
  "Mock adapter whose response map may contain strings or prompt fns by agent name."
  [responses]
  (k/mock-adapter
   (fn [{:keys [agent prompt]}]
     (if (contains? responses (:name agent))
       (let [response (get responses (:name agent))]
         (if (fn? response)
           (response prompt)
           response))
       (str "[" (:name agent) "] " prompt)))))

;; An adapter whose named agents fail, to exercise short-circuiting.
(defn- failing-adapter [fail-names]
  (reify k/Adapter
    (-run [_ agent prompt _]
      (k/result {:agent (:name agent)
                 :ok?   (not (contains? fail-names (:name agent)))
                 :text  (str "[" (:name agent) "] " prompt)}))))

;; An adapter that throws for named agents, to exercise fault isolation.
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
    (is (= {:karcarthy/type :map :planner planner :worker a}
           (o/map planner a)))
    (is (= {:karcarthy/type :reduce
            :mapped {:karcarthy/type :map :branches [a b]}
            :reducer reducer}
           (o/reduce (o/map [a b]) reducer)))
    (is (= {:karcarthy/type :bind :source router :routes {:yes a}}
           (o/bind router {:yes a})))
    (is (= {:karcarthy/type :iterate :worker a :evaluator judge :max-rounds 2}
           (o/iterate a judge :max-rounds 2)))))

(deftest pipe-threads-text
  (testing "each result's text feeds the next step"
    (let [r (o/run h (o/pipe a b c) "hi")]
      (is (k/ok? r))
      (is (= "[c] [b] [a] hi" (:text r))))))

(deftest pipe-short-circuits-on-failure
  (testing "a failing step stops the pipe and is returned"
    (let [r (o/run (failing-adapter #{"b"}) (o/pipe a b c) "hi")]
      (is (not (k/ok? r)))
      (is (= "b" (:agent r)))
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

(deftest reduce-runs-a-reducer-workflow
  (testing "reduce is a workflow node, not a host function stored in data"
    (let [adapter (scripted-adapter
                   {"reducer" (fn [prompt]
                                (->> (:results (edn/read-string prompt))
                                     (map :text)
                                     (str/join "|")))})
          flow    (o/reduce (o/map [a b c]) reducer)
          r       (o/run adapter flow "hi")]
      (is (k/ok? r))
      (is (= "[a] hi|[b] hi|[c] hi" (:text r)))
      (is (= :result (:karcarthy/type (:mapped r))))
      (is (= 3 (count (get-in r [:mapped :results]))))
      (is (= "reducer" (:agent (:reduced r)))))))

(deftest planned-map-reads-structured-edn-subtasks
  (testing "a planner's reply is EDN data, not a scraped bullet list"
    (let [adapter (scripted-adapter
                   {"planner" "{:subtasks [\"alpha\" \"beta\" \"gamma\"]}"})
          flow    (o/map planner a)
          r       (o/run adapter flow "do stuff")]
      (is (k/ok? r))
      (is (= ["alpha" "beta" "gamma"] (:subtasks r)))
      (is (= ["[a] alpha" "[a] beta" "[a] gamma"] (map :text (:results r)))))))

(deftest planned-map-rejects-unstructured-subtasks
  (testing "planner prose is a failed control message"
    (let [adapter (scripted-adapter {"planner" "- alpha\n- beta"})
          r       (o/run adapter (o/map planner a) "do stuff")]
      (is (not (k/ok? r)))
      (is (= :invalid-subtasks (:error r))))))

(deftest planned-map-bounded-concurrency
  (testing "all subtasks run correctly even with a small concurrency bound"
    (let [adapter (scripted-adapter
                   {"planner" "{:subtasks [\"0\" \"1\" \"2\" \"3\" \"4\"]}"})
          flow    (o/map planner a :max-concurrency 2)
          r       (o/run adapter flow "in")]
      (is (= 5 (count (:results r))))
      (is (= (mapv #(str "[a] " %) (range 5)) (mapv :text (:results r)))))))

(deftest planned-map-reduce
  (testing "a reducer workflow combines planned worker results"
    (let [adapter (scripted-adapter
                   {"planner" "{:subtasks [\"p\" \"q\"]}"
                    "reducer" (fn [prompt]
                                (->> (:results (edn/read-string prompt))
                                     (map :text)
                                     (str/join "+")))})
          flow    (o/reduce (o/map planner a) reducer)
          r       (o/run adapter flow "in")]
      (is (= "[a] p+[a] q" (:text r)))
      (is (= ["p" "q"] (get-in r [:mapped :subtasks]))))))

(deftest iterate-accepts-immediately
  (testing "an evaluator accepts by returning EDN {:accept? true}"
    (let [adapter (scripted-adapter {"judge" "{:accept? true}"})
          flow    (o/iterate a judge)
          r       (o/run adapter flow "topic")]
      (is (k/ok? r))
      (is (true? (:accepted? r)))
      (is (= 1 (:rounds r)))
      (is (= "[a] topic" (:text r))))))

(deftest iterate-loops-to-max-rounds
  (testing "EDN feedback drives revision until :max-rounds"
    (let [adapter (scripted-adapter {"judge" "{:accept? false :feedback \"more\"}"})
          flow    (o/iterate a judge :max-rounds 3)
          r       (o/run adapter flow "topic")]
      (is (false? (:accepted? r)))
      (is (= 3 (:rounds r)))
      (is (str/includes? (:text r) "topic")))))

(deftest iterate-accepts-on-later-round
  (testing "the loop revises until the evaluator accepts"
    (let [calls   (atom 0)
          adapter (scripted-adapter
                   {"judge" (fn [_]
                              (if (>= (swap! calls inc) 2)
                                "{:accept? true}"
                                "{:accept? false :feedback \"f\"}"))})
          flow    (o/iterate a judge :max-rounds 5)
          r       (o/run adapter flow "x")]
      (is (true? (:accepted? r)))
      (is (= 2 (:rounds r))))))

(deftest iterate-bails-on-worker-failure
  (testing "a failing worker short-circuits the iterate loop"
    (let [r (o/run (failing-adapter #{"a"}) (o/iterate a judge) "x")]
      (is (not (k/ok? r)))
      (is (= "a" (:agent r))))))

(deftest bind-route-with-agent-source
  (testing "a router source selects a branch with EDN {:route ...}"
    (let [adapter (scripted-adapter {"router" "{:route :billing}"})
          flow    (o/bind router {:billing a :support b})]
      (is (= "[a] refund please" (:text (o/run adapter flow "refund please")))))))

(deftest bind-route-no-match
  (testing "an unmatched route yields a not-ok :no-route result"
    (let [adapter (scripted-adapter {"router" "{:route :nope}"})
          flow    (o/bind router {:yes a})
          r       (o/run adapter flow "hi")]
      (is (not (k/ok? r)))
      (is (= :no-route (:error r)))
      (is (= :nope (:label r))))))

(deftest bind-route-default
  (testing ":default is used when no route matches"
    (let [adapter (scripted-adapter {"router" "{:route :nope}"})
          flow    (o/bind router {:yes a} :default b)
          r       (o/run adapter flow "hi")]
      (is (k/ok? r))
      (is (= "[b] hi" (:text r))))))

(deftest bind-route-rejects-prose
  (testing "router prose is a failed control message"
    (let [adapter (scripted-adapter {"router" "billing"})
          flow    (o/bind router {:billing a})
          r       (o/run adapter flow "hi")]
      (is (not (k/ok? r)))
      (is (= :invalid-route (:error r))))))

(deftest bind-route-is-exact
  (testing "substring and case-insensitive routing are not part of the language"
    (let [adapter (scripted-adapter {"router" "{:route \"BILLING\"}"})
          flow    (o/bind router {"billing" a})
          r       (o/run adapter flow "refund")]
      (is (not (k/ok? r)))
      (is (= :no-route (:error r))))))

(deftest nested-composition
  (testing "workflows nest: a pipe whose step is a map"
    (let [flow (o/pipe a (o/map [b c]))
          r    (o/run h flow "hi")]
      (is (= ["[b] [a] hi" "[c] [a] hi"] (map :text (:results r)))))))

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

(deftest workflow-predicate
  (testing "workflow? recognizes pure data workflows and rejects host functions"
    (is (o/workflow? a))
    (is (o/workflow? (o/pipe a b)))
    (is (o/workflow? (o/map planner a)))
    (is (o/workflow? (o/reduce (o/map [a b]) reducer)))
    (is (not (o/workflow? (assoc a :host/fn (fn [] :x)))))
    (is (not (o/workflow? (assoc (o/pipe a b) :host/fn (fn [] :x)))))
    (is (not (o/workflow? (o/map (fn [_] []) a))))
    (is (not (o/workflow? (o/iterate a (fn [_] {:accept? true})))))
    (is (not (o/workflow? (o/bind (fn [_] :x) {:x a}))))
    (is (not (o/workflow? {:karcarthy/type :pipe
                            :steps [{:karcarthy/type :chain :steps [a]}]})))
    (is (not (o/workflow? {:karcarthy/type :nonsense})))
    (is (not (o/workflow? {:karcarthy/type :default})))
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

(deftest workflow-over-adapter-registry
  (testing "a registry threads through orchestration; each agent uses its adapter"
    (let [reg  {:up      (k/mock-adapter (fn [{:keys [prompt]}] (str/upper-case prompt)))
                :default (k/mock-adapter (fn [{:keys [prompt]}] prompt))}
          flow (o/pipe (k/agent "shout" "i" :adapter :up) (k/agent "echo" "i"))]
      (is (= "HI" (:text (o/run reg flow "hi")))))))

(deftest unknown-node-throws
  (is (thrown? clojure.lang.ExceptionInfo
               (o/run h {:karcarthy/type :nonsense} "hi"))))

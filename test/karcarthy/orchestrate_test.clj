(ns karcarthy.orchestrate-test
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [karcarthy.core :as k]
            [karcarthy.orchestrate :as o]))

;; The default mock runner replies "[<agent-name>] <input>", which makes
;; threading and routing deterministic to assert.
(def ^:private h (k/mock-runner))

(def ^:private a (k/agent "a" "i"))
(def ^:private b (k/agent "b" "i"))
(def ^:private c (k/agent "c" "i"))
(def ^:private planner (k/agent "planner" "reply with {:subtasks [...] }"))
(def ^:private judge (k/agent "judge" "reply with {:accept? ...}"))
(def ^:private router (k/agent "router" "reply with {:route ...}"))
(def ^:private reducer (k/agent "reducer" "reduce source EDN results"))

(defn- scripted-runner
  "Mock runner whose response map may contain strings or prompt fns by agent name."
  [responses]
  (k/mock-runner
   (fn [{:keys [agent prompt]}]
     (if (contains? responses (:name agent))
       (let [response (get responses (:name agent))]
         (if (fn? response)
           (response prompt)
           response))
       (str "[" (:name agent) "] " prompt)))))

;; A runner whose named agents fail, to exercise short-circuiting.
(defn- failing-runner [fail-names]
  (reify k/Runner
    (-run [_ agent prompt _]
      (k/result {:agent (:name agent)
                 :ok?   (not (contains? fail-names (:name agent)))
                 :text  (str "[" (:name agent) "] " prompt)}))))

;; A runner that throws for named agents, to exercise fault isolation.
(defn- throwing-runner [throw-names]
  (reify k/Runner
    (-run [_ agent prompt _]
      (if (contains? throw-names (:name agent))
        (throw (ex-info "boom" {:agent (:name agent)}))
        (k/result {:agent (:name agent)
                   :text  (str "[" (:name agent) "] " prompt)})))))

(deftest constructors-build-data
  (testing "workflows are plain tagged data"
    (is (= {:karcarthy/type :pipe :steps [a b]} (o/pipe a b)))
    (is (= :step (:karcarthy/type (o/step str/trim))))
    (is (= "trim" (:name (o/step str/trim :name "trim"))))
    (is (= {:karcarthy/type :branch :branches [a b]} (o/branch [a b])))
    (is (= {:karcarthy/type :delegate :planner planner :worker a}
           (o/delegate planner a)))
    (is (= {:karcarthy/type :reduce
            :source {:karcarthy/type :branch :branches [a b]}
            :reducer reducer}
           (o/reduce (o/branch [a b]) reducer)))
    (is (= {:karcarthy/type :route :source router :routes {:yes a}}
           (o/route router {:yes a})))
    (is (= {:karcarthy/type :revise :worker a :evaluator judge :max-rounds 2}
           (o/revise a judge :max-rounds 2)))))

(deftest pipe-threads-text
  (testing "each result's text feeds the next step"
    (let [r (o/run h (o/pipe a b c) "hi")]
      (is (k/ok? r))
      (is (= "[c] [b] [a] hi" (:text r))))))

(deftest step-runs-host-function
  (testing "a host Clojure step receives the flowing input directly"
    (let [r (o/run h (o/pipe (o/step str/upper-case :name "up") a) "hi")]
      (is (k/ok? r))
      (is (= "[a] HI" (:text r)))))
  (testing "a host Clojure step can opt into context"
    (let [flow (o/pipe (o/step (fn [{:keys [input node]}]
                                  (str (:name node) ":" input))
                                :name "tag"
                                :context? true)
                       a)
          r    (o/run h flow "hi")]
      (is (= "[a] tag:hi" (:text r)))))
  (testing "a host Clojure step may return a result map"
    (let [r (o/run h (o/step (fn [_] (k/result {:text "done"}))) "hi")]
      (is (= "done" (:text r))))))

(deftest pipe-short-circuits-on-failure
  (testing "a failing step stops the pipe and is returned"
    (let [r (o/run (failing-runner #{"b"}) (o/pipe a b c) "hi")]
      (is (not (k/ok? r)))
      (is (= "b" (:agent r)))
      (is (not (str/includes? (:text r) "[c]"))))))

(deftest branch-runs-workflows
  (testing "branches run on the same input; results are returned in order"
    (let [r (o/run h (o/branch [a b c]) "hi")]
      (is (k/ok? r))
      (is (= 3 (count (:results r))))
      (is (= ["[a] hi" "[b] hi" "[c] hi"] (map :text (:results r))))
      (is (= "[a] hi\n\n[b] hi\n\n[c] hi" (:text r))))))

(deftest branch-ok-requires-all-results
  (testing "one failing branch makes the whole node not-ok"
    (let [r (o/run (failing-runner #{"b"}) (o/branch [a b c]) "hi")]
      (is (not (k/ok? r)))
      (is (= 3 (count (:results r)))))))

(deftest reduce-runs-a-reducer-workflow
  (testing "reduce is a workflow node, not a host function stored in data"
    (let [runner (scripted-runner
                   {"reducer" (fn [prompt]
                                (->> (:results (edn/read-string prompt))
                                     (map :text)
                                     (str/join "|")))})
          flow    (o/reduce (o/branch [a b c]) reducer)
          r       (o/run runner flow "hi")]
      (is (k/ok? r))
      (is (= "[a] hi|[b] hi|[c] hi" (:text r)))
      (is (= :result (:karcarthy/type (:source r))))
      (is (= 3 (count (get-in r [:source :results]))))
      (is (= "reducer" (:agent (:reduced r)))))))

(deftest delegate-reads-structured-edn-subtasks
  (testing "a planner's reply is EDN data, not a scraped bullet list"
    (let [runner (scripted-runner
                   {"planner" "{:subtasks [\"alpha\" \"beta\" \"gamma\"]}"})
          flow    (o/delegate planner a)
          r       (o/run runner flow "do stuff")]
      (is (k/ok? r))
      (is (= ["alpha" "beta" "gamma"] (:subtasks r)))
      (is (= ["[a] alpha" "[a] beta" "[a] gamma"] (map :text (:results r)))))))

(deftest delegate-rejects-unstructured-subtasks
  (testing "planner prose is a failed EDN reply"
    (let [runner (scripted-runner {"planner" "- alpha\n- beta"})
          r       (o/run runner (o/delegate planner a) "do stuff")]
      (is (not (k/ok? r)))
      (is (= :invalid-subtasks (:error r))))))

(deftest delegate-bounded-concurrency
  (testing "all subtasks run correctly even with a small concurrency bound"
    (let [runner (scripted-runner
                   {"planner" "{:subtasks [\"0\" \"1\" \"2\" \"3\" \"4\"]}"})
          flow    (o/delegate planner a :max-concurrency 2)
          r       (o/run runner flow "in")]
      (is (= 5 (count (:results r))))
      (is (= (mapv #(str "[a] " %) (range 5)) (mapv :text (:results r)))))))

(deftest delegate-reduce
  (testing "a reducer workflow combines planned worker results"
    (let [runner (scripted-runner
                   {"planner" "{:subtasks [\"p\" \"q\"]}"
                    "reducer" (fn [prompt]
                                (->> (:results (edn/read-string prompt))
                                     (map :text)
                                     (str/join "+")))})
          flow    (o/reduce (o/delegate planner a) reducer)
          r       (o/run runner flow "in")]
      (is (= "[a] p+[a] q" (:text r)))
      (is (= ["p" "q"] (get-in r [:source :subtasks]))))))

(deftest revise-accepts-immediately
  (testing "an evaluator accepts by returning EDN {:accept? true}"
    (let [runner (scripted-runner {"judge" "{:accept? true}"})
          flow    (o/revise a judge)
          r       (o/run runner flow "topic")]
      (is (k/ok? r))
      (is (true? (:accepted? r)))
      (is (= 1 (:rounds r)))
      (is (= "[a] topic" (:text r))))))

(deftest revise-loops-to-max-rounds
  (testing "EDN feedback drives revision until :max-rounds"
    (let [runner (scripted-runner {"judge" "{:accept? false :feedback \"more\"}"})
          flow    (o/revise a judge :max-rounds 3)
          r       (o/run runner flow "topic")]
      (is (false? (:accepted? r)))
      (is (= 3 (:rounds r)))
      (is (str/includes? (:text r) "topic")))))

(deftest revise-accepts-on-later-round
  (testing "the loop revises until the evaluator accepts"
    (let [calls   (atom 0)
          runner (scripted-runner
                   {"judge" (fn [_]
                              (if (>= (swap! calls inc) 2)
                                "{:accept? true}"
                                "{:accept? false :feedback \"f\"}"))})
          flow    (o/revise a judge :max-rounds 5)
          r       (o/run runner flow "x")]
      (is (true? (:accepted? r)))
      (is (= 2 (:rounds r))))))

(deftest revise-bails-on-worker-failure
  (testing "a failing worker short-circuits the revision loop"
    (let [r (o/run (failing-runner #{"a"}) (o/revise a judge) "x")]
      (is (not (k/ok? r)))
      (is (= "a" (:agent r))))))

(deftest route-with-agent-source
  (testing "a router source selects a branch with EDN {:route ...}"
    (let [runner (scripted-runner {"router" "{:route :billing}"})
          flow    (o/route router {:billing a :support b})]
      (is (= "[a] refund please" (:text (o/run runner flow "refund please")))))))

(deftest route-no-match
  (testing "an unmatched route yields a not-ok :no-route result"
    (let [runner (scripted-runner {"router" "{:route :nope}"})
          flow    (o/route router {:yes a})
          r       (o/run runner flow "hi")]
      (is (not (k/ok? r)))
      (is (= :no-route (:error r)))
      (is (= :nope (:label r))))))

(deftest route-default
  (testing ":default is used when no route matches"
    (let [runner (scripted-runner {"router" "{:route :nope}"})
          flow    (o/route router {:yes a} :default b)
          r       (o/run runner flow "hi")]
      (is (k/ok? r))
      (is (= "[b] hi" (:text r))))))

(deftest route-rejects-prose
  (testing "router prose is a failed EDN reply"
    (let [runner (scripted-runner {"router" "billing"})
          flow    (o/route router {:billing a})
          r       (o/run runner flow "hi")]
      (is (not (k/ok? r)))
      (is (= :invalid-route (:error r))))))

(deftest route-is-exact
  (testing "substring and case-insensitive routing are not part of the language"
    (let [runner (scripted-runner {"router" "{:route \"BILLING\"}"})
          flow    (o/route router {"billing" a})
          r       (o/run runner flow "refund")]
      (is (not (k/ok? r)))
      (is (= :no-route (:error r))))))

(deftest nested-composition
  (testing "workflows nest: a pipe whose step is a branch"
    (let [flow (o/pipe a (o/branch [b c]))
          r    (o/run h flow "hi")]
      (is (= ["[b] [a] hi" "[c] [a] hi"] (map :text (:results r)))))))

;; Records each call's :resume opt and returns a per-agent session id, so we can
;; assert that continuation threads the session forward.
(defn- session-recording-runner [log]
  (reify k/Runner
    (-run [_ agent prompt opts]
      (swap! log conj {:agent (:name agent) :prompt prompt :resume (:resume opts)})
      (k/result {:agent      (:name agent)
                 :text       (str "[" (:name agent) "] " prompt)
                 :session-id (str "sess-" (:name agent))}))))

(deftest continuation-threads-session
  (testing "to inherits source text as input and source session as :resume"
    (let [log (atom [])
          r   (o/run (session-recording-runner log) (o/continue a b) "hi")]
      (is (k/ok? r))
      (is (= "[b] [a] hi" (:text r)))
      (is (= [{:agent "a" :prompt "hi"     :resume nil}
              {:agent "b" :prompt "[a] hi" :resume "sess-a"}]
             @log)))))

(deftest continuation-prompt-override
  (testing ":prompt overrides the handed-off input"
    (let [log (atom [])
          r   (o/run (session-recording-runner log)
                     (o/continue a b :prompt "explicit") "hi")]
      (is (= "[b] explicit" (:text r)))
      (is (= "explicit" (:prompt (second @log)))))))

(deftest continuation-bails-on-source-failure
  (testing "if the source agent fails, continue returns that failure"
    (let [r (o/run (failing-runner #{"a"}) (o/continue a b) "hi")]
      (is (not (k/ok? r)))
      (is (= "a" (:agent r))))))

(deftest branch-isolates-throwing-workflow
  (testing "a branch that throws becomes a not-ok result; siblings are unaffected"
    (let [r (o/run (throwing-runner #{"b"}) (o/branch [a b c]) "hi")]
      (is (not (k/ok? r)))
      (is (= 3 (count (:results r))))
      (is (= [true false true] (mapv k/ok? (:results r))))
      (is (= "[a] hi" (:text (first (:results r)))))
      (is (= "[c] hi" (:text (last (:results r)))))
      (is (= "boom" (:error (second (:results r))))))))

(deftest workflow-predicate
  (testing "workflow? recognizes workflows and only permits host functions in step nodes"
    (is (o/workflow? a))
    (is (o/workflow? (o/step str/trim)))
    (is (o/workflow? (o/pipe a b)))
    (is (o/workflow? (o/pipe a (o/step str/trim) b)))
    (is (o/workflow? (o/delegate planner a)))
    (is (o/workflow? (o/reduce (o/branch [a b]) reducer)))
    (is (not (o/workflow? (assoc a :host/fn (fn [] :x)))))
    (is (not (o/workflow? (assoc (o/pipe a b) :host/fn (fn [] :x)))))
    (is (not (o/workflow? (o/delegate (fn [_] []) a))))
    (is (not (o/workflow? (o/revise a (fn [_] {:accept? true})))))
    (is (not (o/workflow? (o/route (fn [_] :x) {:x a}))))
    (is (not (o/workflow? {:karcarthy/type :step :f "not-a-fn"})))
    (is (not (o/workflow? {:karcarthy/type :step :f str/trim :name :trim})))
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

(deftest workflow-over-runner-registry
  (testing "a registry threads through orchestration; each agent uses its runner"
    (let [reg  {:up      (k/mock-runner (fn [{:keys [prompt]}] (str/upper-case prompt)))
                :default (k/mock-runner (fn [{:keys [prompt]}] prompt))}
          flow (o/pipe (k/agent "shout" "i" :runner :up) (k/agent "echo" "i"))]
      (is (= "HI" (:text (o/run reg flow "hi")))))))

(deftest observe-emits-span-compatible-events
  (testing "workflow and agent events include span ids, parent ids, paths, and attributes"
    (let [events   (atom [])
          observer #(swap! events conj %)
          flow     (o/pipe a b)
          r        (o/run h flow "hi" {:observe observer})
          starts   (filter #(= :start (:event %)) @events)
          finishes (filter #(= :finish (:event %)) @events)
          top      (first starts)
          children (vec (rest starts))]
      (is (k/ok? r))
      (is (= :workflow (:kind top)))
      (is (= "karcarthy.workflow.pipe" (:name top)))
      (is (string? (:span/id top)))
      (is (every? :span/id @events))
      (is (every? :attributes @events))
      (is (= (:span/id top) (:parent/span-id (children 0))))
      (is (= (:span/id (children 0)) (:parent/span-id (children 1))))
      (is (= (:span/id top) (:parent/span-id (children 2))))
      (is (= (:span/id (children 2)) (:parent/span-id (children 3))))
      (is (= [[:steps 0] [:steps 0] [:steps 1] [:steps 1]]
             (mapv :path (filter #(#{:workflow :agent} (:kind %)) children))))
      (is (every? #(contains? % :duration-ms) finishes))
      (is (not-any? #(contains? % :text) @events))
      (is (not-any? #(contains? % :prompt) @events)))))

(deftest unknown-node-throws
  (is (thrown? clojure.lang.ExceptionInfo
               (o/run h {:karcarthy/type :nonsense} "hi"))))

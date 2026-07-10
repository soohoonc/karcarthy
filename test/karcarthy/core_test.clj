(ns karcarthy.core-test
  (:refer-clojure :exclude [run!])
  (:require [clojure.spec.alpha :as s]
            [clojure.test :refer [deftest is testing]]
            [karcarthy :as k]))

(s/def ::text string?)
(s/def ::tool-input (s/keys :req-un [::text]))
(s/def ::value int?)
(s/def ::context (s/keys :req-un [::value]))

(defn scripted-model
  [& responses]
  (let [remaining (atom responses)]
    (k/fake-model
     (fn [_]
       (let [response (first @remaining)]
         (swap! remaining next)
         (if (fn? response) (response) response))))))

(k/deftool uppercase
  {:description "Uppercase text."
   :input ::tool-input
   :output string?}
  [{:keys [text]}]
  (.toUpperCase ^String text))

(deftest agent-and-tool-values
  (let [leaf (k/agent {:name "leaf"
                       :model {:id "fake" :transport (scripted-model "ok")}
                       :instructions "answer"})
        program (k/agent {:name "program" :input string? :output string?}
                         [input] (str input "!"))]
    (is (k/agent? leaf))
    (is (k/agent? program))
    (is (k/tool? uppercase))
    (is (= "uppercase" (:name uppercase)))
    (is (seq (k/source-form program)))
    (is (seq (k/expanded-form program)))))

(deftest configuration-fails-closed
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown"
                        (k/agent {:name "bad"
                                  :model :x
                                  :instructions "x"
                                  :wat true})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #":model and :instructions"
                        (k/agent {:name "bad"})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown configuration"
                        (k/agent {:name "bad-loop"
                                  :model :x
                                  :instructions "x"
                                  :prepare-step identity})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown configuration"
                        (k/agent {:name "old-context-name"
                                  :environment map?}
                                 [_] nil)))
  (is (thrown-with-msg? IllegalArgumentException #"requires a function"
                        (k/fake-model :not-a-function)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #":input contract"
                        (k/tool {:name "x" :description "x"}
                                [input] input))))

(deftest model-backed-agent-final-output
  (let [agent (k/agent {:name "answer"
                        :model {:id "fake"
                                :transport (scripted-model
                                            {:type :final :output "done"
                                             :usage {:input-tokens 3
                                                     :output-tokens 2}})}
                        :instructions "answer"
                        :input string?
                        :output string?})
        run (k/run! agent "task")]
    (is (= :completed (:status run)))
    (is (= "done" (:output run)))
    (is (= 1 (get-in run [:usage :model-calls])))
    (is (= 3 (get-in run [:usage :input-tokens])))
    (is (= 2 (get-in run [:usage :output-tokens])))))

(deftest model-stream-deltas-are-run-events
  (let [transport
        {:stream (fn [_ emit!]
                   (emit! {:type :text-delta :delta "hel"})
                   (emit! {:type :text-delta :delta "lo"})
                   {:type :final :output "hello"})}
        agent (k/agent {:name "streaming"
                        :model {:id "fake" :transport transport}
                        :instructions "Answer."
                        :output string?})
        observed (atom [])
        run (k/run! agent "hi" {:observe #(swap! observed conj %)})]
    (is (= :completed (:status run)))
    (is (= "hello" (:output run)))
    (is (= ["hel" "lo"]
           (->> @observed
                (filter #(= :model/text-delta (:type %)))
                (mapv :delta))))))

(deftest model-streaming-can-be-disabled-per-model
  (let [path (atom nil)
        transport
        {:complete (fn [_]
                     (reset! path :complete)
                     {:type :final :output "done"})
         :stream (fn [_ _]
                   (reset! path :stream)
                   {:type :final :output "wrong"})}
        agent (k/agent {:name "complete-only"
                        :model {:id "fake"
                                :transport transport
                                :stream false}
                        :instructions "Answer."
                        :output string?})
        run (k/run! agent "hi")]
    (is (= :completed (:status run)))
    (is (= "done" (:output run)))
    (is (= :complete @path))))

(deftest named-transport-is-resolved-independently-from-provider
  (let [transport (scripted-model "done")
        agent (k/agent {:name "routed"
                        :model {:id "provider/model"
                                :provider :example-provider
                                :transport :example-transport}
                        :instructions "answer"
                        :output string?})
        run (k/run! agent nil
                    {:model-transports {:example-transport transport}})
        requested (first (filter #(= :model/requested (:type %))
                                 (:events run)))]
    (is (= :completed (:status run)))
    (is (= "done" (:output run)))
    (is (= {:provider :example-provider
            :transport :example-transport
            :id "provider/model"}
           (:model requested)))))

(deftest session-manages-conversation-history
  (let [seen (atom [])
        session (k/memory-session)
        model (k/fake-model
               (fn [request]
                 (swap! seen conj (:messages request))
                 {:type :final :output "ok"}))
        agent (k/agent {:name "remembering"
                        :model {:id "fake" :transport model}
                        :instructions "remember"
                        :output string?})]
    (let [first-run (k/run! agent "first" {:session session})
          second-run (k/run! agent "second" {:session session})]
      (is (not (contains? first-run :state)))
      (is (= 1 (count (filter #(= :session/updated (:type %))
                              (:events first-run)))))
      (is (= 1 (count (filter #(= :session/updated (:type %))
                              (:events second-run))))))
    (is (k/session? session))
    (is (= 4 (count (k/get-items session))))
    (is (= 1 (count (first @seen))))
    (is (= 3 (count (second @seen))))
    (is (= "first" (get-in @seen [1 0 :content])))
    (is (= "ok" (get-in @seen [1 1 :content])))
    (is (= "second" (get-in @seen [1 2 :content])))))

(deftest session-operations
  (let [session (k/memory-session {:id "session_test"
                                   :items [{:role :user :content "one"}]})]
    (is (= "session_test" (k/session-id session)))
    (k/add-items! session [{:role :assistant :content "two"}])
    (is (= "two" (:content (k/pop-item! session))))
    (is (= 1 (count (k/get-items session))))
    (k/clear-session! session)
    (is (empty? (k/get-items session)))))

(deftest run-requires-a-session-implementation
  (let [agent (k/agent {:name "program"} [input] input)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must implement"
                          (k/run! agent nil {:session {}})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown configuration"
                          (k/run! agent nil {:state {}})))))

(deftest provider-continuation-avoids-replaying-history-within-a-run
  (let [seen (atom [])
        model (k/fake-model
               (fn [request]
                 (swap! seen conj (:messages request))
                 (if (= 1 (count @seen))
                   {:type :tool-calls
                    :calls [{:id "call_1"
                             :name "uppercase"
                             :input {:text "hello"}}]
                    :provider-state {:cursor "provider-state"}}
                   {:type :final :output "ok"})))
        agent (k/agent {:name "continued"
                        :model {:id "fake" :transport model}
                        :instructions "continue"
                        :tools [uppercase]
                        :output string?})
        run (k/run! agent "first")]
    (is (= :completed (:status run)))
    (is (= 1 (count (second @seen))))
    (is (= :tool (get-in @seen [1 0 :role])))))

(deftest native-model-tool-loop
  (let [seen (atom [])
        model (k/fake-model
               (fn [request]
                 (swap! seen conj request)
                 (if (= 1 (count @seen))
                   {:type :tool-calls
                    :calls [{:id "call_1"
                             :name "uppercase"
                             :input {:text "hello"}}]}
                   {:type :final
                    :output (get-in request [:messages 0 :content])})))
        agent (k/agent {:name "tool-user"
                        :model {:id "fake" :transport model}
                        :instructions "use the tool"
                        :tools [uppercase]
                        :output string?})
        run (k/run! agent "hello")]
    (is (= :completed (:status run)))
    (is (= "HELLO" (:output run)))
    (is (= 2 (count @seen)))
    (is (= "object" (get-in @seen [0 :tools 0 :parameters :type])))
    (is (= "string"
           (get-in @seen [0 :tools 0 :parameters :properties "text" :type])))
    (is (= ["text"]
           (get-in @seen [0 :tools 0 :parameters :required])))
    (is (= :tool (get-in @seen [1 :messages 0 :role])))
    (is (= [:model/requested :model/completed
            :tool/started :tool/completed
            :model/requested :model/completed]
           (->> (:events run)
                (map :type)
                (filter #{:model/requested :model/completed
                          :tool/started :tool/completed})
                vec)))))

(deftest structured-output-is-parsed-and-checked
  (s/def ::answer string?)
  (s/def ::report (s/keys :req-un [::answer]))
  (let [agent (k/agent {:name "json"
                        :model {:id "fake"
                                :transport (scripted-model
                                            "{\"answer\":\"yes\"}")}
                        :instructions "json"
                        :output ::report})
        run (k/run! agent nil)]
    (is (= :completed (:status run)))
    (is (= {:answer "yes"} (:output run)))))

(deftest contract-errors-become-failed-runs
  (let [bad-input (k/agent {:name "input"
                            :input string?
                            :output string?}
                           [x] x)
        bad-output (k/agent {:name "output"
                             :input any?
                             :output string?}
                            [_] 42)]
    (is (= :contract (get-in (k/run! bad-input 42) [:error :kind])))
    (is (= :agent-input (get-in (k/run! bad-input 42) [:error :phase])))
    (is (= :contract (get-in (k/run! bad-output nil) [:error :kind])))
    (is (= :agent-output (get-in (k/run! bad-output nil) [:error :phase])))))

(deftest rejected-model-output-is-not-added-to-session
  (let [session (k/memory-session)
        agent (k/agent {:name "bad-model-output"
                        :model {:id "fake"
                                :transport (scripted-model 42)}
                        :instructions "Return text."
                        :output string?})
        run (k/run! agent "hello" {:session session})]
    (is (= :failed (:status run)))
    (is (empty? (k/get-items session)))))

(deftest context-is-local-and-contracted
  (let [seen (atom nil)
        agent (k/agent {:name "context"
                        :context ::context
                        :input any?
                        :output int?}
                       [_]
                       (:value (k/context)))]
    (is (= 7 (:output (k/run! agent nil {:context {:value 7}
                                         :observe #(reset! seen %)}))))
    (is (= :run/completed (:type @seen)))
    (is (= :contract (get-in (k/run! agent nil {:context {}})
                             [:error :kind])))))

(deftest instructions-are-model-visible-and-context-is-local
  (let [requests (atom [])
        model (k/fake-model
               (fn [request]
                 (swap! requests conj request)
                 {:type :final :output "ok"}))
        agent (k/agent
               {:name "assembled"
                :model {:id "fake" :transport model}
                :instructions
                (fn [{:keys [context]}]
                  (str "user=" (:user-id context)))
                :context map?
                :output string?})
        run (k/run! agent "hello" {:context {:user-id "u1"}})]
    (is (= :completed (:status run)))
    (is (= "user=u1" (get-in @requests [0 :instructions])))
    (is (= "hello"
           (get-in @requests [0 :messages 0 :content])))
    (is (nil? (get-in @requests [0 :context])))))

(deftest instructions-must-resolve-to-a-string
  (let [agent (k/agent
               {:name "bad-instructions"
                :model {:id "fake"
                        :transport (scripted-model "unused")}
                :instructions (fn [_] {:not "instructions"})})
        run (k/run! agent "hello")]
    (is (= :failed (:status run)))
    (is (= :instructions (get-in run [:error :kind])))))

(deftest custom-program-composes-agents
  (let [writer (k/agent {:name "writer" :input string? :output string?}
                        [x] (str x " draft"))
        editor (k/agent {:name "editor" :input string? :output string?}
                        [x] (str x " edited"))
        team (k/agent {:name "team" :input string? :output string?}
                      [x]
                      (let [draft (:output (k/run! writer x))]
                        (:output (k/run! editor draft))))
        run (k/run! team "memo")]
    (is (= "memo draft edited" (:output run)))
    (is (= ["team"]
           (->> (:events run)
                (filter #(= :agent/started (:type %)))
                (map :agent)
                vec)))))

(deftest ordinary-clojure-parallelism-preserves-order
  (let [child (fn [name delay-ms]
                (k/agent {:name name :input int? :output int?}
                         [x] (Thread/sleep delay-ms) x))
        slow (child "slow" 30)
        fast (child "fast" 1)
        team (k/agent {:name "parallel" :output vector?}
                      [_]
                      (->> [(future (k/run! slow 1))
                            (future (k/run! fast 2))]
                           (mapv deref)
                           (mapv :output)))
        run (k/run! team nil)]
    (is (= :completed (:status run)))
    (is (= [1 2] (:output run)))))

(deftest depth-and-call-budgets
  (let [leaf (k/agent {:name "leaf" :input any? :output string?} [_] "ok")
        parent (k/agent {:name "parent"
                         :model {:id "fake"
                                 :transport
                                 (scripted-model
                                  {:type :tool-calls
                                   :calls [{:id "child"
                                            :name "leaf"
                                            :input nil}]})}
                         :instructions "Call leaf."
                         :tools [(k/as-tool leaf)]
                         :output string?})
        looping (k/agent {:name "looping"
                          :model {:id "fake"
                                  :transport (scripted-model
                                              {:type :tool-calls :calls []})}
                          :instructions "loop"
                          :max-turns 1})
        depth-run (k/run! parent nil {:limits {:agent-depth 0}})
        budget-run (k/run! looping nil {:limits {:model-calls 0}})]
    (is (= :failed (:status depth-run)))
    (is (= :agent-depth
           (get-in depth-run [:error :phase])))
    (is (= :failed (:status budget-run)))
    (is (= :budget (get-in budget-run [:error :kind])))))

(deftest cancellation-and-deadline
  (let [agent (k/agent {:name "work" :output string?}
                       [_] (Thread/sleep 20) "done")]
    (is (= :cancelled (:status (k/run! agent nil {:cancel (atom true)}))))
    (is (= :failed (:status (k/run! agent nil
                                     {:limits {:deadline-ms 1}}))))))

(deftest approval-policy
  (let [dangerous (k/tool {:name "dangerous"
                           :description "Do a dangerous thing."
                           :input map?
                           :output string?
                           :approval :always}
                          [_] "done")
        model #(k/fake-model
                (let [n (atom 0)]
                  (fn [request]
                    (if (= 1 (swap! n inc))
                      {:type :tool-calls
                       :calls [{:id "c" :name "dangerous" :input {}}]}
                      {:type :final
                       :output (get-in request [:messages 0 :content])}))))
        make-agent #(k/agent {:name "approval"
                              :model {:id "fake" :transport (model)}
                              :instructions "call"
                              :tools [dangerous]
                              :output string?})]
    (is (= :failed (:status (k/run! (make-agent) nil))))
    (is (= "done" (:output (k/run! (make-agent) nil
                                    {:approval (constantly true)}))))))

(deftest once-approval-is-reused-across-turns
  (let [effect (k/tool {:name "effect"
                        :description "Perform one approved effect."
                        :input map?
                        :output string?
                        :approval :once}
                       [_] "ok")
        turn (atom 0)
        approvals (atom 0)
        model (k/fake-model
               (fn [_]
                 (case (swap! turn inc)
                   1 {:type :tool-calls
                      :calls [{:id "c1" :name "effect" :input {}}]}
                   2 {:type :tool-calls
                      :calls [{:id "c2" :name "effect" :input {}}]}
                   {:type :final :output "done"})))
        agent (k/agent {:name "once"
                        :model {:id "fake" :transport model}
                        :instructions "Call twice."
                        :tools [effect]
                        :output string?})
        run (k/run! agent nil
                    {:approval (fn [_] (swap! approvals inc) true)})]
    (is (= :completed (:status run)))
    (is (= 1 @approvals))
    (is (= 1 (count (filter #(= :tool/approval-reused (:type %))
                            (:events run)))))))

(deftest agents-as-tools
  (let [child (k/agent {:name "child" :input string? :output string?}
                       [x] (str x "!"))
        child-tool (k/as-tool child {:name "delegate"})
        n (atom 0)
        parent (k/agent {:name "parent"
                         :model {:id "fake"
                                 :transport
                                 (k/fake-model
                                  (fn [request]
                                    (if (= 1 (swap! n inc))
                                      {:type :tool-calls
                                       :calls [{:id "c"
                                                :name "delegate"
                                                :input "hi"}]}
                                      {:type :final
                                       :output (get-in request [:messages 0 :content])})))}
                         :instructions "delegate"
                         :tools [child-tool]
                         :output string?})]
    (is (= "hi!" (:output (k/run! parent nil))))))

(deftest guardrails-reject
  (let [agent (k/agent {:name "guarded"
                        :input string?
                        :output string?
                        :guardrails {:input [(fn [_ value]
                                               (not= value "blocked"))]}}
                       [x] x)]
    (is (= :completed (:status (k/run! agent "ok"))))
    (is (= :guardrail (get-in (k/run! agent "blocked") [:error :kind])))))

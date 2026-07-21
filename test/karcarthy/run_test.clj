(ns karcarthy.run-test
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [karcarthy :as k]))

(s/def ::text string?)
(s/def ::tool-input (s/keys :req-un [::text]))
(s/def ::value int?)
(s/def ::context (s/keys :req-un [::value]))

(defn scripted-model
  [& responses]
  (let [remaining (atom responses)]
    (k/mock-model
     (fn [_]
       (let [response (first @remaining)]
         (swap! remaining next)
         (if (fn? response) (response) response))))))

(k/deftool uppercase
  {:description "Uppercase text."
   :input-schema ::tool-input
   :output-schema string?}
  [{:keys [text]}]
  (.toUpperCase ^String text))

(deftest agent-and-tool-values
  (let [leaf (k/agent {:name "leaf"
                       :model {:id "fake" :transport (scripted-model "ok")}
                       :instructions "answer"})]
    (is (k/agent? leaf))
    (is (k/tool? uppercase))
    (is (= "uppercase" (:name uppercase)))
    (is (= "Uppercase text." (:description uppercase)))
    (is (nil? (:config uppercase)))
    (is (seq (k/definition leaf)))
    (is (seq (k/expansion leaf)))
    (is (not (contains? leaf :body)))))

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
                        (k/agent {:name "bad-stop-condition"
                                  :model :x
                                  :instructions "x"
                                  :stop-when identity})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown configuration"
                        (k/agent {:name "old-context-name"
                                  :model :x
                                  :instructions "x"
                                  :environment map?})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown configuration"
                        (k/agent {:name "duplicate-schema-name"
                                  :model :x
                                  :instructions "x"
                                  :input string?})))
  (is (thrown-with-msg? IllegalArgumentException #"requires a function"
                        (k/mock-model :not-a-function)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #":input-schema"
                        (k/tool {:name "x" :description "x"}
                                [input] input)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #":input-schema"
                        (k/tool {:name "opaque"
                                 :description "Opaque input."
                                 :input-schema any?}
                                [input] input)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown configuration"
                        (k/tool {:name "old-tool-options"
                                 :description "Old Tool options."
                                 :input-schema map?
                                 :retry 2}
                                [input] input)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #":max-turns"
                        (k/agent {:name "bad-turns"
                                  :model "fake"
                                  :instructions "x"
                                  :max-turns 0})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"model-calls"
                        (k/agent {:name "bad-limits"
                                  :model "fake"
                                  :instructions "x"
                                  :limits {:model-calls -1}})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #":input-guardrails"
                        (k/agent {:name "bad-guardrail"
                                  :model "fake"
                                  :instructions "x"
                                  :input-guardrails [:not-a-function]})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #":needs-approval"
                        (k/tool {:name "bad-approval"
                                 :description "Invalid approval policy."
                                 :input-schema map?
                                 :needs-approval :alway}
                                [input] input)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #":to-model-output"
                        (k/tool {:name "bad-projector"
                                 :description "Invalid output projector."
                                 :input-schema map?
                                 :to-model-output :not-a-function}
                                [input] input))))

(deftest agent-final-output
  (let [agent (k/agent {:name "answer"
                        :model {:id "fake"
                                :transport (scripted-model
                                            {:type :final :output "done"
                                             :usage {:input-tokens 3
                                                     :output-tokens 2}})}
                        :instructions "answer"
                        :input-schema string?
                        :output-schema string?})
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
                        :output-schema string?})
        observed (atom [])
        run (k/run! agent "hi" {:on-event #(swap! observed conj %)})]
    (is (= :completed (:status run)))
    (is (= "hello" (:output run)))
    (is (= ["hel" "lo"]
           (->> @observed
                (filter #(= :model/text-delta (:type %)))
                (mapv :delta))))
    (is (empty? (filter #(= :model/text-delta (:type %))
                        (:events run))))))

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
                        :output-schema string?})
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
                        :output-schema string?})
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
        session (k/session)
        model (k/mock-model
               (fn [request]
                 (swap! seen conj (:messages request))
                 {:type :final :output "ok"}))
        agent (k/agent {:name "remembering"
                        :model {:id "fake" :transport model}
                        :instructions "remember"
                        :output-schema string?})]
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
  (let [session (k/session {:id "session_test"
                                   :items [{:role :user :content "one"}]})]
    (is (= "session_test" (k/session-id session)))
    (k/add-items! session [{:role :assistant :content "two"}])
    (is (= "two" (:content (k/pop-item! session))))
    (is (= 1 (count (k/get-items session))))
    (k/clear-session! session)
    (is (empty? (k/get-items session)))))

(deftest run-requires-a-session-implementation
  (let [agent (k/agent {:name "agent"
                        :model {:id "fake"
                                :transport (scripted-model "ok")}
                        :instructions "answer"})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must implement"
                          (k/run! agent nil {:session {}})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown configuration"
                          (k/run! agent nil {:state {}})))))

(deftest output-returns-only-completed-run-output
  (let [completed-agent
        (k/agent {:name "completed-output"
                  :model {:id "fake" :transport (scripted-model nil)}
                  :instructions "Return nil."
                  :output-schema any?})
        failed-agent
        (k/agent {:name "failed-output"
                  :model {:id "fake"
                          :transport (k/mock-model
                                      (fn [_]
                                        (throw (ex-info "model failed" {}))))}
                  :instructions "Fail."})
        completed (k/run! completed-agent nil)
        failed (k/run! failed-agent nil)]
    (is (nil? (k/output completed)))
    (try
      (k/output failed)
      (is false "failed Run output should throw")
      (catch clojure.lang.ExceptionInfo error
        (is (= "model failed" (ex-message error)))
        (is (= failed (:run (ex-data error))))))))

(deftest internal-eval-namespaces-are-not-run-options
  (let [agent (k/agent {:name "agent"
                        :model {:id "fake"
                                :transport (scripted-model "ok")}
                        :instructions "answer"})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown configuration"
                          (k/run! agent nil
                                  {:eval-namespace 'example.agent-code})))))

(deftest provider-continuation-avoids-replaying-history-within-a-run
  (let [seen (atom [])
        model (k/mock-model
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
                        :output-schema string?})
        run (k/run! agent "first")]
    (is (= :completed (:status run)))
    (is (= 1 (count (second @seen))))
    (is (= :tool (get-in @seen [1 0 :role])))))

(deftest native-model-tool-loop
  (let [seen (atom [])
        model (k/mock-model
               (fn [request]
                 (swap! seen conj request)
                 (if (= 1 (count @seen))
                   {:type :tool-calls
                    :calls [{:id "call_1"
                             :name "uppercase"
                             :input {:text "hello"}}]}
                   {:type :final
                    :output (-> request :messages last :content)})))
        agent (k/agent {:name "tool-user"
                        :model {:id "fake" :transport model}
                        :instructions "use the tool"
                        :tools [uppercase]
                        :output-schema string?})
        run (k/run! agent "hello")]
    (is (= :completed (:status run)))
    (is (= "HELLO" (:output run)))
    (is (= 2 (count @seen)))
    (is (= "object" (get-in @seen [0 :tools 0 :parameters :type])))
    (is (= "string"
           (get-in @seen [0 :tools 0 :parameters :properties "text" :type])))
    (is (= ["text"]
           (get-in @seen [0 :tools 0 :parameters :required])))
    (is (= [:user :assistant :tool]
           (mapv :role (get-in @seen [1 :messages]))))
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
                        :output-schema ::report})
        run (k/run! agent nil)]
    (is (= :completed (:status run)))
    (is (= {:answer "yes"} (:output run)))))

(deftest json-looking-string-output-remains-a-string
  (let [value "{\"answer\":\"yes\"}"
        agent (k/agent {:name "literal-json"
                        :model {:id "fake"
                                :transport (scripted-model value)}
                        :instructions "Return the literal string."
                        :output-schema string?})
        run (k/run! agent nil)]
    (is (= :completed (:status run)) (pr-str (:error run)))
    (is (= value (:output run)))))

(deftest structured-union-output-is-parsed
  (s/def ::nullable-answer string?)
  (s/def ::nullable-report
    (s/nilable (s/keys :req-un [::nullable-answer])))
  (let [agent (k/agent {:name "nullable-json"
                        :model {:id "fake"
                                :transport
                                (scripted-model
                                 "{\"nullable-answer\":\"yes\"}")}
                        :instructions "Return structured output."
                        :output-schema ::nullable-report})
        run (k/run! agent nil)]
    (is (= :completed (:status run)) (pr-str (:error run)))
    (is (= {:nullable-answer "yes"} (:output run)))))

(deftest malformed-structured-output-is-a-model-failure
  (let [agent (k/agent {:name "malformed-json"
                        :model {:id "fake"
                                :transport (scripted-model "{not-json")}
                        :instructions "Return structured output."
                        :output-schema map?})
        run (k/run! agent nil)]
    (is (= :failed (:status run)))
    (is (= :model (get-in run [:error :kind])))
    (is (= :response (get-in run [:error :phase])))))

(deftest root-run-options-validate-before-execution
  (let [agent (k/agent {:name "invalid-options"
                        :model {:id "fake"
                                :transport (scripted-model "unused")}
                        :instructions "Answer."})]
    (doseq [[options message]
            [[{:on-event 42} #":on-event"]
             [{:approval true} #":approval"]
             [{:model-transports []} #":model-transports"]
             [{:cancel :not-a-token} #":cancel"]]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo message
                            (k/run! agent nil options))))))

(deftest schema-errors-become-failed-runs
  (let [bad-input (k/agent {:name "input"
                            :model {:id "fake"
                                    :transport (scripted-model "unused")}
                            :instructions "answer"
                            :input-schema string?
                            :output-schema string?})
        bad-output (k/agent {:name "output"
                             :model {:id "fake"
                                     :transport (scripted-model 42)}
                             :instructions "answer"
                             :input-schema any?
                             :output-schema string?})]
    (is (= :schema (get-in (k/run! bad-input 42) [:error :kind])))
    (is (= :agent-input (get-in (k/run! bad-input 42) [:error :phase])))
    (is (= :schema (get-in (k/run! bad-output nil) [:error :kind])))
    (is (= :agent-output (get-in (k/run! bad-output nil) [:error :phase])))))

(deftest rejected-model-output-is-not-added-to-session
  (let [session (k/session)
        agent (k/agent {:name "bad-model-output"
                        :model {:id "fake"
                                :transport (scripted-model 42)}
                        :instructions "Return text."
                        :output-schema string?})
        run (k/run! agent "hello" {:session session})]
    (is (= :failed (:status run)))
    (is (empty? (k/get-items session)))))

(deftest context-is-local-and-schema-validated
  (let [seen (atom nil)
        agent (k/agent {:name "context"
                        :model {:id "fake"
                                :transport
                                (k/mock-model
                                 (fn [_]
                                   {:type :final
                                    :output (:value (k/context))}))}
                        :instructions "Return the local value."
                        :context-schema ::context
                        :input-schema any?
                        :output-schema int?})]
    (is (= 7 (:output (k/run! agent nil {:context {:value 7}
                                         :on-event #(reset! seen %)}))))
    (is (= :run/completed (:type @seen)))
    (is (= :schema (get-in (k/run! agent nil {:context {}})
                             [:error :kind])))))

(deftest instructions-are-model-visible-and-context-is-local
  (let [requests (atom [])
        model (k/mock-model
               (fn [request]
                 (swap! requests conj request)
                 {:type :final :output "ok"}))
        agent (k/agent
               {:name "assembled"
                :model {:id "fake" :transport model}
                :instructions
                (fn [{:keys [context]}]
                  (str "user=" (:user-id context)))
                :context-schema map?
                :output-schema string?})
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

(deftest ordinary-functions-compose-agents
  (let [append-model
        (fn [suffix]
          (k/mock-model
           (fn [request]
             {:type :final
              :output (str (-> request :messages last :content) suffix)})))
        writer (k/agent {:name "writer"
                         :model {:id "fake"
                                 :transport (append-model " draft")}
                         :instructions "write"
                         :input-schema string?
                         :output-schema string?})
        editor (k/agent {:name "editor"
                         :model {:id "fake"
                                 :transport (append-model " edited")}
                         :instructions "edit"
                         :input-schema string?
                         :output-schema string?})
        review (fn [input]
                 (->> (k/run! writer input)
                      :output
                      (k/run! editor)
                      :output))]
    (is (= "memo draft edited" (review "memo")))))

(deftest ordinary-clojure-parallelism-preserves-order
  (let [child (fn [name delay-ms]
                (k/agent
                 {:name name
                  :model {:id "fake"
                          :transport
                          (k/mock-model
                           (fn [request]
                             (Thread/sleep delay-ms)
                             {:type :final
                              :output (-> request :messages last :content)}))}
                  :instructions "return the input"
                  :input-schema int?
                  :output-schema int?}))
        slow (child "slow" 30)
        fast (child "fast" 1)
        run-team (fn []
                   (->> [(future (k/run! slow 1))
                         (future (k/run! fast 2))]
                        (mapv deref)
                        (mapv :output)))]
    (is (= [1 2] (run-team)))))

(deftest depth-and-call-budgets
  (let [leaf (k/agent {:name "leaf"
                       :model {:id "fake"
                               :transport (scripted-model "ok")}
                       :instructions "answer"
                       :input-schema string?
                       :output-schema string?})
        parent (k/agent {:name "parent"
                         :model {:id "fake"
                                 :transport
                                 (scripted-model
                                  {:type :tool-calls
                                   :calls [{:id "child"
                                            :name "leaf"
                                            :input {:input "work"}}]})}
                         :instructions "Call leaf."
                         :agents [leaf]
                         :output-schema string?})
        looping (k/agent {:name "looping"
                          :model {:id "fake"
                                  :transport (scripted-model
                                              {:type :tool-calls :calls []})}
                          :instructions "loop"
                          :max-turns 1})
        depth-run (k/run! parent nil {:limits {:depth 0}})
        budget-run (k/run! looping nil {:limits {:model-calls 0}})]
    (is (= :failed (:status depth-run)))
    (is (= :depth
           (get-in depth-run [:error :phase])))
    (is (= :failed (:status budget-run)))
    (is (= :budget (get-in budget-run [:error :kind])))))

(deftest cancellation-and-deadline
  (let [agent (k/agent
               {:name "work"
                :model {:id "fake"
                        :transport
                        (k/mock-model
                         (fn [_]
                           (Thread/sleep 20)
                           {:type :final :output "done"}))}
                :instructions "work"
                :output-schema string?})]
    (is (= :cancelled (:status (k/run! agent nil {:cancel (atom true)}))))
    (is (= :failed (:status (k/run! agent nil
                                     {:limits {:deadline-ms 1}}))))))

(deftest approval-policy
  (let [dangerous (k/tool {:name "dangerous"
                           :description "Do a dangerous thing."
                           :input-schema map?
                           :output-schema string?
                           :needs-approval :always}
                          [_] "done")
        model #(k/mock-model
                (let [n (atom 0)]
                  (fn [request]
                    (if (= 1 (swap! n inc))
                      {:type :tool-calls
                       :calls [{:id "c" :name "dangerous" :input {}}]}
                      {:type :final
                       :output (-> request :messages last :content)}))))
        make-agent #(k/agent {:name "approval"
                              :model {:id "fake" :transport (model)}
                              :instructions "call"
                              :tools [dangerous]
                              :output-schema string?})]
    (is (= :failed (:status (k/run! (make-agent) nil))))
    (is (= "done" (:output (k/run! (make-agent) nil
                                    {:approval (constantly true)}))))))

(deftest dynamic-approval-policy-fails-closed
  (let [executed? (atom false)
        dangerous (k/tool {:name "dangerous"
                           :description "Use a dynamic approval policy."
                           :input-schema map?
                           :needs-approval (fn [_ _] :alway)}
                          [_]
                          (reset! executed? true))
        model (scripted-model
               {:type :tool-calls
                :calls [{:id "call_1" :name "dangerous" :input {}}]})
        agent (k/agent {:name "invalid-dynamic-approval"
                        :model {:id "fake" :transport model}
                        :instructions "Call the Tool."
                        :tools [dangerous]})
        run (k/run! agent nil)]
    (is (= :failed (:status run)))
    (is (= :configuration (get-in run [:error :phase])))
    (is (false? @executed?))))

(deftest application-events-cannot-replace-lineage
  (let [model (k/mock-model
               (fn [_]
                 (k/emit! {:type :application/checkpoint
                           :karcarthy/type :spoofed
                           :run-id "spoofed"
                           :agent-id "spoofed"
                           :depth 99})
                 {:type :final :output "done"}))
        agent (k/agent {:name "event-lineage"
                        :model {:id "fake" :transport model}
                        :instructions "Answer."
                        :output-schema string?})
        run (k/run! agent nil)
        event (first (filter #(= :application/checkpoint (:type %))
                             (:events run)))]
    (is (= :completed (:status run)))
    (is (= :event (:karcarthy/type event)))
    (is (= (:id run) (:run-id event)))
    (is (not= "spoofed" (:agent-id event)))
    (is (= 0 (:depth event)))))

(deftest once-approval-is-reused-across-turns
  (let [effect (k/tool {:name "effect"
                        :description "Perform one approved effect."
                        :input-schema map?
                        :output-schema string?
                        :needs-approval :once}
                       [_] "ok")
        turn (atom 0)
        approvals (atom 0)
        model (k/mock-model
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
                        :output-schema string?})
        run (k/run! agent nil
                    {:approval (fn [_] (swap! approvals inc) true)})]
    (is (= :completed (:status run)))
    (is (= 1 @approvals))
    (is (= 1 (count (filter #(= :tool/approval-reused (:type %))
                            (:events run)))))))

(deftest available-agents-are-model-callable
  (let [child-input {:type "object"
                     :properties {"text" {:type "string"}}
                     :required ["text"]
                     :additionalProperties false}
        child (k/agent {:name "child"
                        :description "Add punctuation to text."
                        :model {:id "fake"
                                :transport
                                (k/mock-model
                                 (fn [request]
                                   {:type :final
                                    :output
                                    (str (get-in (last (:messages request))
                                                 [:content :text])
                                         "!")}))}
                        :instructions "Add punctuation."
                        :input-schema child-input
                        :output-schema string?})
        n (atom 0)
        requests (atom [])
        parent (k/agent {:name "parent"
                         :model {:id "fake"
                                 :transport
                                 (k/mock-model
                                  (fn [request]
                                    (swap! requests conj request)
                                    (if (= 1 (swap! n inc))
                                      {:type :tool-calls
                                       :calls [{:id "c"
                                                :name "child"
                                                :input {:text "hi"}}]}
                                      {:type :final
                                       :output (-> request :messages last :content)})))}
                         :instructions "delegate"
                         :agents [child]
                         :output-schema string?})]
    (is (= "hi!" (:output (k/run! parent nil))))
    (is (= "child" (get-in @requests [0 :tools 0 :name])))
    (is (= ["text"]
           (get-in @requests [0 :tools 0 :parameters :required])))))

(deftest eval-tool-has-a-complete-dynamic-manual
  (let [request (atom nil)
        model (k/mock-model
               (fn [value]
                 (reset! request value)
                 {:type :final :output "done"}))
        verifier (k/agent {:name "finding-verifier"
                           :description "Independently verify one proposed finding."
                           :model {:id "fake"
                                   :transport (scripted-model "verified")}
                           :instructions "Verify the finding."
                           :input-schema string?
                           :output-schema string?})
        parent (k/agent
                {:name "parent"
                 :model {:transport :captured
                         :provider :test
                         :id "test-model"}
                 :instructions "Complete the assigned task."
                 :tools [uppercase]
                 :agents [verifier]
                 :output-schema string?})
        run (k/run! parent "work"
                    {:model-transports {:captured model}})
        eval-tool (first (filter #(= "eval" (:name %))
                                 (:tools @request)))
        description (:description eval-tool)]
    (is (= :completed (:status run)))
    (is (= "Complete the assigned task." (:instructions @request)))
    (doseq [section ["## When to use"
                     "## Tool input"
                     "## Creating an Agent"
                     "## Run behavior"
                     "## Example: parallel reviewers"
                     "## Available model configuration"
                     "## Available Tools"
                     "## Available Agents"]]
      (is (str/includes? description section) section))
    (is (str/includes? description
                       "{:transport :captured, :provider :test, :id \"test-model\"}"))
    (is (str/includes? description "(run! agent-value agent-input)"))
    (is (str/includes? description "(output run)"))
    (is (str/includes? description "`uppercase`"))
    (is (str/includes? description "`finding-verifier`"))
    (is (= ["code"]
           (get-in eval-tool [:parameters :required])))
    (is (= #{"code"}
           (set (keys (get-in eval-tool [:parameters :properties])))))))

(deftest guardrails-reject
  (let [input-guarded
        (k/agent {:name "input-guarded"
                  :model {:id "fake" :transport (scripted-model "ok")}
                  :instructions "answer"
                  :input-schema string?
                  :output-schema string?
                  :input-guardrails [(fn [_ value]
                                       (not= value "blocked"))]})
        output-guarded
        (k/agent {:name "output-guarded"
                  :model {:id "fake" :transport (scripted-model "blocked")}
                  :instructions "answer"
                  :output-schema string?
                  :output-guardrails [(fn [_ value]
                                        (not= value "blocked"))]})]
    (is (= :completed (:status (k/run! input-guarded "ok"))))
    (is (= :guardrail
           (get-in (k/run! input-guarded "blocked") [:error :kind])))
    (is (= :guardrail
           (get-in (k/run! output-guarded nil) [:error :kind])))))

(deftest explicit-json-schema-uses-standard-keywords
  (let [schema {:type "object"
                :properties
                {"numbers" {:type "array" :items {:type "integer"}}
                 "mode" {:anyOf [{:const "fast"} {:const "safe"}]}}
                :required ["numbers" "mode"]
                :additionalProperties false}]
    (is (k/schema-valid? schema {:numbers [1 2] :mode "fast"}))
    (is (not (k/schema-valid? schema {:numbers [1 "two"] :mode "fast"})))
    (is (not (k/schema-valid? schema {:numbers [1] :mode "other"})))
    (is (not (k/schema-valid? schema
                              {:numbers [1] :mode "safe" :extra true}))))
  (is (k/schema-valid? {:$schema "http://json-schema.org/draft-07/schema#"
                        :type "string"
                        :minLength 2}
                       "ok"))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid JSON Schema"
                        (k/schema-valid? {:type "not-a-json-type"} nil))))

(deftest tool-execution-errors-return-to-the-model
  (let [attempt (k/tool {:name "attempt"
                         :description "Attempt fallible work."
                         :input-schema map?
                         :output-schema string?}
                        [_]
                        (throw (ex-info "temporary failure" {})))
        seen (atom nil)
        calls (atom 0)
        model (k/mock-model
               (fn [request]
                 (if (= 1 (swap! calls inc))
                   {:type :tool-calls
                    :calls [{:id "call_1" :name "attempt" :input {}}]}
                   (do
                     (reset! seen (last (:messages request)))
                     {:type :final :output "recovered"}))))
        agent (k/agent {:name "recovering"
                        :model {:id "fake" :transport model}
                        :instructions "Recover from the Tool error."
                        :tools [attempt]
                        :output-schema string?})
        run (k/run! agent nil)
        failed-event (first (filter #(= :tool/failed (:type %))
                                    (:events run)))]
    (is (= :completed (:status run)))
    (is (= "recovered" (:output run)))
    (is (true? (:is-error @seen)))
    (is (= "temporary failure" (get-in @seen [:content :error])))
    (is (true? (get-in failed-event [:error :recoverable?])))))

(deftest in-flight-cancellation-interrupts-the-model
  (let [cancel (atom false)
        started (promise)
        interrupted (promise)
        model (k/mock-model
               (fn [_]
                 (deliver started true)
                 (try
                   (Thread/sleep 10000)
                   {:type :final :output "late"}
                   (catch InterruptedException error
                     (deliver interrupted true)
                     (throw error)))))
        agent (k/agent {:name "cancellable"
                        :model {:id "fake" :transport model}
                        :instructions "Wait."
                        :output-schema string?})
        result (future (k/run! agent nil {:cancel cancel}))]
    (try
      (is (true? (deref started 1000 false)))
      (reset! cancel true)
      (let [run (deref result 2000 ::timeout)]
        (is (not= ::timeout run))
        (is (= :cancelled (:status run))))
      (is (true? (deref interrupted 1000 false)))
      (finally
        (future-cancel result)))))

(deftest fatal-tool-failure-cancels-sibling-tools
  (let [slow-started (promise)
        interrupted (promise)
        invalid (k/tool {:name "invalid"
                         :description "Return invalid output."
                         :input-schema map?
                         :output-schema string?}
                        [_]
                        (deref slow-started 1000 nil)
                        42)
        slow (k/tool {:name "slow"
                      :description "Wait for a long time."
                      :input-schema map?
                      :output-schema string?}
                     [_]
                     (deliver slow-started true)
                     (try
                       (Thread/sleep 10000)
                       "late"
                       (catch InterruptedException error
                         (deliver interrupted true)
                         (throw error))))
        model (scripted-model
               {:type :tool-calls
                :calls [{:id "slow" :name "slow" :input {}}
                        {:id "bad" :name "invalid" :input {}}]})
        agent (k/agent {:name "supervisor"
                        :model {:id "fake" :transport model}
                        :instructions "Call both Tools."
                        :tools [invalid slow]
                        :output-schema string?})
        run (k/run! agent nil)]
    (is (= :failed (:status run)))
    (is (= :schema (get-in run [:error :kind])))
    (is (true? (deref interrupted 1000 false)))))

(deftest top-level-runs-serialize-shared-session-access
  (let [conversation (k/session)
        message-counts (atom [])
        model (k/mock-model
               (fn [request]
                 (swap! message-counts conj (count (:messages request)))
                 (Thread/sleep 30)
                 {:type :final :output "ok"}))
        agent (k/agent {:name "serialized"
                        :model {:id "fake" :transport model}
                        :instructions "Answer."
                        :output-schema string?})
        runs [(future (k/run! agent "one" {:session conversation}))
              (future (k/run! agent "two" {:session conversation}))]]
    (is (= [:completed :completed]
           (mapv (comp :status deref) runs)))
    (is (= [1 3] (sort @message-counts)))
    (is (= 4 (count (k/get-items conversation))))))

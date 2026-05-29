(ns karcarthy.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [karcarthy.core :as k]))

(deftest agent-construction
  (testing "agent builds a tagged data map with optional fields"
    (let [a (k/agent "researcher" "Research thoroughly."
                     :model "sonnet" :tools ["WebSearch" "WebFetch"])]
      (is (= :agent (:karcarthy/type a)))
      (is (= "researcher" (:name a)))
      (is (= "sonnet" (:model a)))
      (is (= ["WebSearch" "WebFetch"] (:tools a)))
      (is (k/agent? a))))
  (testing "optional fields are omitted when not supplied"
    (let [a (k/agent "x" "y")]
      (is (= #{:karcarthy/type :name :instructions} (set (keys a))))
      (is (k/agent? a)))))

(deftest agent-validation
  (testing "agent? rejects malformed values"
    (is (not (k/agent? {:karcarthy/type :agent})))           ; missing fields
    (is (not (k/agent? {:karcarthy/type :agent :name ""      ; blank name
                        :instructions "i"})))
    (is (some? (k/explain-agent {:karcarthy/type :agent})))
    (is (nil? (k/explain-agent (k/agent "a" "i"))))))

(deftest run-via-mock-echo
  (testing "default mock runner echoes the prompt tagged with agent name"
    (let [r (k/run-agent (k/mock-runner) (k/agent "echo" "e") "hello")]
      (is (k/ok? r))
      (is (= :result (:karcarthy/type r)))
      (is (= "echo" (:agent r)))
      (is (= "[echo] hello" (:text r))))))

(deftest run-with-custom-responder
  (testing "mock runner can return scripted replies"
    (let [runner (k/mock-runner (fn [{:keys [prompt]}] (str "got:" prompt)))
          r      (k/run-agent runner (k/agent "a" "i") "x")]
      (is (= "got:x" (:text r))))))

(deftest run-validates-agent
  (testing "run-agent throws on a malformed agent"
    (is (thrown? clojure.lang.ExceptionInfo
                 (k/run-agent (k/mock-runner)
                              {:karcarthy/type :agent}
                              "x")))))

(k/defagent test-researcher
  "Research questions thoroughly."
  :model "sonnet" :tools ["WebSearch" "WebFetch"])

(deftest defagent-macro
  (testing "defagent defs a valid agent named after the symbol"
    (is (k/agent? test-researcher))
    (is (= "test-researcher" (:name test-researcher)))
    (is (= "Research questions thoroughly." (:instructions test-researcher)))
    (is (= "sonnet" (:model test-researcher)))
    (is (= ["WebSearch" "WebFetch"] (:tools test-researcher))))
  (testing "instructions become the var's docstring"
    (is (= "Research questions thoroughly."
           (:doc (meta #'test-researcher))))))

(deftest runner-registry-resolution
  (testing "an agent's :runner id selects from a registry; :default is fallback"
    (let [reg {:a       (k/mock-runner (fn [{:keys [prompt]}] (str "A:" prompt)))
               :b       (k/mock-runner (fn [{:keys [prompt]}] (str "B:" prompt)))
               :default (k/mock-runner (fn [{:keys [prompt]}] (str "D:" prompt)))}]
      (is (= "A:hi" (:text (k/run-agent reg (k/agent "x" "i" :runner :a) "hi"))))
      (is (= "B:hi" (:text (k/run-agent reg (k/agent "y" "i" :runner :b) "hi"))))
      (is (= "D:hi" (:text (k/run-agent reg (k/agent "z" "i") "hi"))))   ; no :runner
      (is (= :a (:runner (k/agent "x" "i" :runner :a)))))))

(deftest runner-registry-missing-id
  (testing "a missing id with no :default throws"
    (is (thrown? clojure.lang.ExceptionInfo
                 (k/run-agent {:a (k/mock-runner)} (k/agent "x" "i" :runner :nope) "hi")))))

(deftest single-runner
  (testing "passing a Runner directly works"
    (is (= "[e] hi" (:text (k/run-agent (k/mock-runner) (k/agent "e" "i") "hi"))))))

(deftest harness-compatibility
  (testing "old Harness, :harness and mock-harness names still work"
    (let [reg {:old (k/mock-harness (fn [{:keys [prompt]}] (str "old:" prompt)))}]
      (is (= "old:hi" (:text (k/run-agent reg (k/agent "x" "i" :harness :old) "hi"))))
      (is (= :old (:harness (k/agent "x" "i" :harness :old))))
      (is (satisfies? k/Runner (reify k/Harness
                                  (-run [_ agent prompt opts]
                                    (k/run-agent (k/mock-runner) agent prompt opts))))))))

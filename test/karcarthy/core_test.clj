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
  (testing "default mock adapter echoes the prompt tagged with agent name"
    (let [r (k/run-agent (k/mock-adapter) (k/agent "echo" "e") "hello")]
      (is (k/ok? r))
      (is (= :result (:karcarthy/type r)))
      (is (= "echo" (:agent r)))
      (is (= "[echo] hello" (:text r))))))

(deftest run-with-custom-responder
  (testing "mock adapter can return scripted replies"
    (let [adapter (k/mock-adapter (fn [{:keys [prompt]}] (str "got:" prompt)))
          r       (k/run-agent adapter (k/agent "a" "i") "x")]
      (is (= "got:x" (:text r))))))

(deftest run-validates-agent
  (testing "run-agent throws on a malformed agent"
    (is (thrown? clojure.lang.ExceptionInfo
                 (k/run-agent (k/mock-adapter)
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

(deftest adapter-registry-resolution
  (testing "an agent's :adapter id selects from a registry; :default is fallback"
    (let [reg {:a       (k/mock-adapter (fn [{:keys [prompt]}] (str "A:" prompt)))
               :b       (k/mock-adapter (fn [{:keys [prompt]}] (str "B:" prompt)))
               :default (k/mock-adapter (fn [{:keys [prompt]}] (str "D:" prompt)))}]
      (is (= "A:hi" (:text (k/run-agent reg (k/agent "x" "i" :adapter :a) "hi"))))
      (is (= "B:hi" (:text (k/run-agent reg (k/agent "y" "i" :adapter :b) "hi"))))
      (is (= "D:hi" (:text (k/run-agent reg (k/agent "z" "i") "hi"))))   ; no :adapter
      (is (= :a (:adapter (k/agent "x" "i" :adapter :a)))))))

(deftest adapter-registry-missing-id
  (testing "a missing id with no :default throws"
    (is (thrown? clojure.lang.ExceptionInfo
                 (k/run-agent {:a (k/mock-adapter)} (k/agent "x" "i" :adapter :nope) "hi")))))

(deftest single-adapter
  (testing "passing one adapter directly works"
    (is (= "[e] hi" (:text (k/run-agent (k/mock-adapter) (k/agent "e" "i") "hi"))))))

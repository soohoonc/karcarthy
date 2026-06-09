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

(deftest subagent-construction
  (testing "subagent builds runner-native delegation config"
    (let [s (k/subagent "security-reviewer"
                        "Use for auth, secrets, and permission review."
                        "Review like a security owner."
                        :tools ["Read" "Grep" "Glob"]
                        :disallowed-tools ["Write" "Edit"]
                        :model "sonnet"
                        :permission-mode :plan
                        :mcp-servers ["github"]
                        :max-turns 5
                        :skills ["review"]
                        :background? false
                        :effort :high
                        :reasoning-effort :medium
                        :sandbox-mode :read-only
                        :nicknames ["Sec"])]
      (is (= :subagent (:karcarthy/type s)))
      (is (= "security-reviewer" (:name s)))
      (is (= "Use for auth, secrets, and permission review." (:description s)))
      (is (= ["Read" "Grep" "Glob"] (:tools s)))
      (is (= ["Write" "Edit"] (:disallowed-tools s)))
      (is (false? (:background? s)))
      (is (k/subagent? s)))))

(deftest subagent-validation
  (testing "subagent? rejects malformed values"
    (is (not (k/subagent? {:karcarthy/type :subagent
                           :name "x"
                           :description "d"})))
    (is (some? (k/explain-subagent {:karcarthy/type :subagent})))
    (is (nil? (k/explain-subagent (k/subagent "a" "d" "i"))))))

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
          r       (k/run-agent runner (k/agent "a" "i") "x")]
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

(k/defsubagent test-reviewer
  "Use for focused review."
  "Review code and report concrete findings."
  :tools ["Read" "Grep"])

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

(deftest defsubagent-macro
  (testing "defsubagent defs a valid subagent named after the symbol"
    (is (k/subagent? test-reviewer))
    (is (= "test-reviewer" (:name test-reviewer)))
    (is (= "Use for focused review." (:description test-reviewer)))
    (is (= ["Read" "Grep"] (:tools test-reviewer))))
  (testing "instructions become the var's docstring"
    (is (= "Review code and report concrete findings."
           (:doc (meta #'test-reviewer))))))

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
  (testing "passing one runner directly works"
    (is (= "[e] hi" (:text (k/run-agent (k/mock-runner) (k/agent "e" "i") "hi"))))))

(deftest fn-runner-coerces-clojure-return-values
  (testing "a Clojure function receives input directly by default"
    (let [runner (k/fn-runner (fn [input] (str "fn:" input)))
          r      (k/run-agent runner (k/agent "f" "i") "hi")]
      (is (k/ok? r))
      (is (= "fn:hi" (:text r)))))
  (testing "a Clojure function can opt into runner context"
    (let [runner (k/fn-runner
                  (fn [{:keys [agent input opts]}]
                    (str (:name agent) ":" input ":" (:suffix opts)))
                  {:context? true})
          r      (k/run-agent runner (k/agent "f" "i") "hi" {:suffix "ok"})]
      (is (= "f:hi:ok" (:text r)))))
  (testing "a Clojure function may return a result map"
    (let [runner (k/fn-runner (fn [_] (k/result {:agent "custom" :text "done"})))
          r      (k/run-agent runner (k/agent "f" "i") "hi")]
      (is (= "custom" (:agent r)))
      (is (= "done" (:text r))))))

(deftest observer-errors-do-not-break-agent-runs
  (testing "observer hooks are best-effort"
    (let [r (k/run-agent (k/mock-runner)
                         (k/agent "e" "i")
                         "hi"
                         {:observe (fn [_] (throw (ex-info "observer failed" {})))})]
      (is (k/ok? r))
      (is (= "[e] hi" (:text r))))))

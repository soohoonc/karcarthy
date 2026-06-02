(ns karcarthy.adapter.command-test
  (:require [clojure.test :refer [deftest is testing]]
            [karcarthy.core :as k]
            [karcarthy.adapter.command :as cmd]))

(deftest fixed-command-echoes-stdin
  (testing "`cat` returns the prompt as the result text (trimmed)"
    (let [h (cmd/command-adapter ["cat"])
          r (k/run-agent h (k/agent "echoer" "i") "hello world")]
      (is (k/ok? r))
      (is (= "echoer" (:agent r)))
      (is (= "hello world" (:text r))))))

(deftest command-transforms-input
  (testing "`tr` uppercases the piped prompt"
    (let [h (cmd/command-adapter ["tr" "a-z" "A-Z"])
          r (k/run-agent h (k/agent "shout" "i") "quiet please")]
      (is (= "QUIET PLEASE" (:text r))))))

(deftest per-agent-command-selection
  (testing "a fn picks the argv per agent"
    (let [h (cmd/command-adapter
             (fn [a] (if (= "up" (:name a)) ["tr" "a-z" "A-Z"] ["cat"])))]
      (is (= "HI" (:text (k/run-agent h (k/agent "up" "i") "hi"))))
      (is (= "hi" (:text (k/run-agent h (k/agent "plain" "i") "hi")))))))

(deftest nonzero-exit-is-not-ok
  (testing "a failing command yields a not-ok result"
    (let [h (cmd/command-adapter ["false"])
          r (k/run-agent h (k/agent "x" "i") "p")]
      (is (not (k/ok? r)))
      (is (= 1 (get-in r [:raw :exit]))))))

(deftest no-trim-option
  (testing ":trim? false preserves stdout whitespace"
    (let [h (cmd/command-adapter ["cat"] {:trim? false})
          r (k/run-agent h (k/agent "raw" "i") "x\n")]
      (is (= "x\n" (:text r))))))

(deftest command-timeout
  (testing "a slow command is reported as a not-ok timeout"
    (let [h (cmd/command-adapter ["sleep" "10"] {:timeout-ms 300})
          r (k/run-agent h (k/agent "slow" "i") "x")]
      (is (not (k/ok? r)))
      (is (= "command timed out" (:error r)))
      (is (true? (get-in r [:raw :timed-out?]))))))

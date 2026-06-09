(ns karcarthy.runner.process-test
  (:require [clojure.test :refer [deftest is testing]]
            [karcarthy.core :as k]
            [karcarthy.runner.process :as proc-runner]))

(deftest fixed-command-echoes-stdin
  (testing "`cat` returns the prompt as the result text (trimmed)"
    (let [h (proc-runner/process-runner ["cat"])
          r (k/run-agent h (k/agent "echoer" "i") "hello world")]
      (is (k/ok? r))
      (is (= "echoer" (:agent r)))
      (is (= "hello world" (:text r))))))

(deftest command-transforms-input
  (testing "`tr` uppercases the piped prompt"
    (let [h (proc-runner/process-runner ["tr" "a-z" "A-Z"])
          r (k/run-agent h (k/agent "shout" "i") "quiet please")]
      (is (= "QUIET PLEASE" (:text r))))))

(deftest per-agent-command-selection
  (testing "a fn picks the argv per agent"
    (let [h (proc-runner/process-runner
             (fn [a] (if (= "up" (:name a)) ["tr" "a-z" "A-Z"] ["cat"])))]
      (is (= "HI" (:text (k/run-agent h (k/agent "up" "i") "hi"))))
      (is (= "hi" (:text (k/run-agent h (k/agent "plain" "i") "hi")))))))

(deftest nonzero-exit-is-not-ok
  (testing "a failing command yields a not-ok result"
    (let [h (proc-runner/process-runner ["false"])
          r (k/run-agent h (k/agent "x" "i") "p")]
      (is (not (k/ok? r)))
      (is (= 1 (get-in r [:raw :exit]))))))

(deftest no-trim-option
  (testing ":trim? false preserves stdout whitespace"
    (let [h (proc-runner/process-runner ["cat"] {:trim? false})
          r (k/run-agent h (k/agent "raw" "i") "x\n")]
      (is (= "x\n" (:text r))))))

(deftest process-timeout
  (testing "a slow process is reported as a not-ok timeout"
    (let [h (proc-runner/process-runner ["sleep" "10"] {:timeout-ms 300})
          r (k/run-agent h (k/agent "slow" "i") "x")]
      (is (not (k/ok? r)))
      (is (= "process timed out" (:error r)))
      (is (true? (get-in r [:raw :timed-out?]))))))

(deftest shell-runner-runs-command-string
  (testing "a shell command string reads the prompt from stdin"
    (let [h (proc-runner/shell-runner "tr a-z A-Z")
          r (k/run-agent h (k/agent "shell" "i") "hello")]
      (is (k/ok? r))
      (is (= "HELLO" (:text r)))
      (is (= :shell (get-in r [:raw :runner]))))))

(deftest per-agent-shell-selection
  (testing "a fn picks the shell command per agent"
    (let [h (proc-runner/shell-runner
             (fn [a] (if (= "up" (:name a)) "tr a-z A-Z" "cat")))]
      (is (= "HI" (:text (k/run-agent h (k/agent "up" "i") "hi"))))
      (is (= "hi" (:text (k/run-agent h (k/agent "plain" "i") "hi")))))))

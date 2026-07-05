(ns karcarthy.runner.process-test
  (:require [clojure.test :refer [deftest is testing]]
            [karcarthy.core :as k]
            [karcarthy.orchestrate :as o]
            [karcarthy.runner.process :as proc-runner]))

(deftest fixed-command-echoes-stdin
  (testing "`cat` returns the prompt as the result text (trimmed)"
    (let [h (proc-runner/process-runner ["cat"])
          r (k/run-agent h (k/agent {:name "echoer" :instructions "i"}) "hello world")]
      (is (k/ok? r))
      (is (= "echoer" (:agent r)))
      (is (= "hello world" (:text r))))))

(deftest command-transforms-input
  (testing "`tr` uppercases the piped prompt"
    (let [h (proc-runner/process-runner ["tr" "a-z" "A-Z"])
          r (k/run-agent h (k/agent {:name "shout" :instructions "i"}) "quiet please")]
      (is (= "QUIET PLEASE" (:text r))))))

(deftest nonzero-exit-is-not-ok
  (testing "a failing command yields a not-ok result"
    (let [h (proc-runner/process-runner ["false"])
          r (k/run-agent h (k/agent {:name "x" :instructions "i"}) "p")]
      (is (not (k/ok? r)))
      (is (= 1 (get-in r [:raw :exit]))))))

(deftest no-trim-option
  (testing ":trim? false preserves stdout whitespace"
    (let [h (proc-runner/process-runner ["cat"] {:trim? false})
          r (k/run-agent h (k/agent {:name "raw" :instructions "i"}) "x\n")]
      (is (= "x\n" (:text r))))))

(deftest process-timeout
  (testing "a slow process is reported as a not-ok timeout"
    (let [h (proc-runner/process-runner ["sleep" "10"] {:timeout-ms 300})
          r (k/run-agent h (k/agent {:name "slow" :instructions "i"}) "x")]
      (is (not (k/ok? r)))
      (is (= "process timed out" (:error r)))
      (is (true? (get-in r [:raw :timed-out?]))))))

(deftest run-deadline-does-not-loosen-runner-timeout
  (let [runner (proc-runner/process-runner ["sleep" "10"] {:timeout-ms 150})
        started (System/currentTimeMillis)
        r (o/run {:runner runner
                  :workflow (k/agent {:name "slow" :instructions "i"})
                  :input "x"
                  :options {:run-timeout-ms 2000}})
        elapsed (- (System/currentTimeMillis) started)]
    (is (not (k/ok? r)))
    (is (= "process timed out" (:error r)))
    (is (< elapsed 1000))))

(deftest process-runner-runs-shell-command-string
  (testing "a shell command string reads the prompt from stdin"
    (let [h (proc-runner/process-runner "tr a-z A-Z")
          r (k/run-agent h (k/agent {:name "shell" :instructions "i"}) "hello")]
      (is (k/ok? r))
      (is (= "HELLO" (:text r)))
      (is (= :process (get-in r [:raw :runner])))
      (is (= :shell (get-in r [:raw :mode]))))))

(deftest process-runner-rejects-agent-aware-command-builders
  (testing "command construction is runner config, not a function of k/agent"
    (is
     (thrown-with-msg?
      clojure.lang.ExceptionInfo
      #"argv vector or shell command string"
      (proc-runner/process-runner (fn [_] ["cat"]))))))

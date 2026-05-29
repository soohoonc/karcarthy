(ns karcarthy.runner-test
  (:require [clojure.test :refer [deftest is testing]]
            [karcarthy.core :as k]
            [karcarthy.runner.claude :as claude]
            [karcarthy.runner.command :as command]
            [karcarthy.runner.openai :as openai]))

(deftest command-runner-namespace
  (testing "the preferred command runner namespace is usable directly"
    (let [r (k/run-agent (command/command-runner ["cat"])
                         (k/agent "echo" "i")
                         "hi")]
      (is (k/ok? r))
      (is (= "hi" (:text r))))))

(deftest runner-wrapper-helpers
  (testing "preferred runner namespaces expose the same pure helpers"
    (is (string? (:doc (meta #'command/command-runner))))
    (is (= ["claude" "-p" "p" "--output-format" "json"
            "--append-system-prompt" "i"]
           (claude/claude-command (k/agent "a" "i") "p" {})))
    (is (= {:name "a" :instructions "i" :input "p"}
           (openai/openai-request (k/agent "a" "i") "p" {})))))

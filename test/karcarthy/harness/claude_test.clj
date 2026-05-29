(ns karcarthy.harness.claude-test
  (:require [clojure.test :refer [deftest is testing]]
            [karcarthy.core :as k]
            [karcarthy.harness.claude :as cc]))

(defn- after
  "The argv element immediately following `flag`, or nil if `flag` is absent."
  [argv flag]
  (second (drop-while #(not= % flag) argv)))

(deftest command-building-defaults
  (testing "agent fields map to the expected flags"
    (let [a    (k/agent "researcher" "Research well."
                        :model "sonnet" :tools ["WebSearch" "WebFetch"])
          argv (cc/claude-command a "find X" {})]
      (is (= ["claude" "-p" "find X"] (subvec argv 0 3)))
      (is (= "json" (after argv "--output-format")))
      (is (= "Research well." (after argv "--append-system-prompt")))
      (is (= "sonnet" (after argv "--model")))
      (is (= "WebSearch,WebFetch" (after argv "--allowedTools")))
      (is (nil? (after argv "--system-prompt"))))))

(deftest command-building-options
  (testing "opts control bin, system-prompt mode, turns, permissions, extras"
    (let [argv (cc/claude-command
                (k/agent "a" "i") "p"
                {:claude-bin         "/opt/node22/bin/claude"
                 :system-prompt-mode :replace
                 :max-turns          3
                 :permission-mode    :bypassPermissions
                 :extra-args         ["--add-dir" "/tmp"]})]
      (is (= "/opt/node22/bin/claude" (first argv)))
      (is (= "i" (after argv "--system-prompt")))
      (is (nil? (after argv "--append-system-prompt")))
      (is (= "3" (after argv "--max-turns")))
      (is (= "bypassPermissions" (after argv "--permission-mode")))
      (is (= "/tmp" (after argv "--add-dir"))))))

(deftest command-omits-absent-options
  (testing "optional flags are absent when the agent/opts don't supply them"
    (let [argv (cc/claude-command (k/agent "a" "i") "p" {})]
      (is (nil? (after argv "--model")))
      (is (nil? (after argv "--allowedTools")))
      (is (nil? (after argv "--max-turns")))
      (is (nil? (after argv "--permission-mode"))))))

;; A real (trimmed) payload from `claude -p --output-format json`.
(def ^:private sample-success
  (str "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,"
       "\"result\":\"pong\",\"session_id\":\"19f413cf\",\"num_turns\":1,"
       "\"total_cost_usd\":0.078,\"usage\":{\"input_tokens\":3}}"))

(deftest parse-result-extracts-fields
  (let [r (cc/parse-result "tester" sample-success)]
    (is (k/ok? r))
    (is (= :result (:karcarthy/type r)))
    (is (= "tester" (:agent r)))
    (is (= "pong" (:text r)))
    (is (= "19f413cf" (:session-id r)))
    (is (= 0.078 (:cost-usd r)))
    (is (= {:input_tokens 3} (:usage r)))))

(deftest parse-result-flags-errors
  (let [r (cc/parse-result "t" "{\"is_error\":true,\"result\":\"boom\"}")]
    (is (not (k/ok? r)))
    (is (= "boom" (:text r)))))

;; Live test — only runs when KARCARTHY_LIVE is set, to avoid spending tokens
;; (and needing network/auth) on a normal `clojure -M:test`.
;;
;; This validates karcarthy's responsibility — build command -> spawn -> auth ->
;; capture stdout -> parse into a result — i.e. the integration boundary. It
;; deliberately does NOT assert the model's turn-by-turn behavior: whether a
;; trivial prompt resolves in one turn or wanders into tool use depends on the
;; ambient environment (available tools, any discovered CLAUDE.md), which is
;; outside the harness's contract. The deterministic `parse-result` tests above
;; already cover both the success and error payload shapes.
(deftest ^:live live-claude-roundtrip
  (when (System/getenv "KARCARTHY_LIVE")
    (testing "a real claude -p call round-trips through the harness and parses"
      (let [tmp (str (System/getProperty "java.io.tmpdir") "/karcarthy-live")
            _   (.mkdirs (java.io.File. tmp))
            h   (cc/claude-harness {:system-prompt-mode :replace
                                    :max-turns          6
                                    :dir                tmp})
            r   (k/run-agent h
                             (k/agent "ponger"
                                      (str "You are an echo bot. Do not use any "
                                           "tools. Reply with exactly one word: pong.")
                                      :model "sonnet")
                             "ping")]
        (is (= :result (:karcarthy/type r)))
        (is (string? (:session-id r)) "captures a session id")
        (is (number? (:cost-usd r))   "captures cost")
        (is (= "result" (get-in r [:raw :type])) "parses the JSON payload")
        (is (contains? #{"success" "error_max_turns"} (:subtype r)))
        ;; If it did resolve cleanly, the final text should be present.
        (when (k/ok? r)
          (is (string? (:text r))))))))

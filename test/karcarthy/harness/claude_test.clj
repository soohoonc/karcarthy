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

(deftest command-streaming-and-session-flags
  (testing "stream-json, resume, continue and partial-messages flags"
    (let [argv (cc/claude-command (k/agent "a" "i") "p"
                                  {:output-format    "stream-json"
                                   :resume           "S123"
                                   :continue?        true
                                   :partial-messages? true})]
      (is (= "stream-json" (after argv "--output-format")))
      (is (some #{"--verbose"} argv))   ; required for stream-json under --print
      (is (= "S123" (after argv "--resume")))
      (is (some #{"--continue"} argv))
      (is (some #{"--include-partial-messages"} argv)))))

;; A subprocess that emits JSONL like `claude -p --output-format stream-json`,
;; including a non-JSON line that must be ignored.
(def ^:private stream-script
  (str "printf '%s\\n' "
       "'{\"type\":\"system\",\"subtype\":\"init\"}' "
       "'oops not json' "
       "'{\"type\":\"assistant\"}' "
       "'{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,"
       "\"result\":\"hi\",\"session_id\":\"S1\",\"total_cost_usd\":0.01}'"))

(deftest read-stream-parses-and-callbacks
  (testing "events parse, on-event fires per event, garbage skipped, result found"
    (let [seen (atom [])
          {:keys [events exit result]}
          (cc/read-stream ["bash" "-c" stream-script]
                          {:on-event #(swap! seen conj (:type %))})]
      (is (zero? exit))
      (is (= ["system" "assistant" "result"] @seen))  ; "oops not json" skipped
      (is (= 3 (count events)))
      (is (= "hi" (:result result)))
      (is (= "S1" (:session_id result))))))

(deftest streaming-result-event-becomes-result
  (testing "the terminal result event maps to a karcarthy result"
    (let [{:keys [result]} (cc/read-stream ["bash" "-c" stream-script] {})
          r (cc/result-map->result "streamer" result)]
      (is (k/ok? r))
      (is (= "hi" (:text r)))
      (is (= "S1" (:session-id r)))
      (is (= 0.01 (:cost-usd r))))))

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

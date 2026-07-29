(load-file "examples/coding/main.clj")

(ns karcarthy.examples-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [example.coding :as coding]
            [karcarthy :as k])
  (:import [java.io BufferedReader StringReader StringWriter]))

(deftest coding-options-are-explicit
  (let [defaults (coding/parse-options [])
        configured
        (coding/parse-options
         ["cwd=/tmp" "dynamic=true" "approval=never" "model=test-model"])]
    (is (= (System/getProperty "user.dir") (:cwd defaults)))
    (is (false? (:dynamic? defaults)))
    (is (= :ask (:approval defaults)))
    (is (= {:cwd "/tmp"
            :dynamic? true
            :approval :never
            :model-id "test-model"}
           configured)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"name=value"
                        (coding/parse-options ["/tmp"])))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"true or false"
                        (coding/parse-options ["dynamic=sometimes"])))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"ask or never"
                        (coding/parse-options ["approval=always"]))))

(deftest dynamic-mode-explicitly-adds-eval
  (let [plain (coding/coding-agent {:cwd "." :approval :ask})
        dynamic (coding/coding-agent {:cwd "."
                                      :approval :never
                                      :dynamic? true})
        policies (into {} (map (juxt :name :needs-approval))
                       (:tools plain))]
    (is (not-any? #(= :eval-tool (:karcarthy/type %)) (:tools plain)))
    (is (some #(= :eval-tool (:karcarthy/type %)) (:tools dynamic)))
    (is (= {"read" :never
            "write" :always
            "edit" :always
            "bash" :always
            "search" :never}
           policies))))

(deftest coding-prompt-follows-standard-coding-agent-guidance
  (doseq [guidance ["You are a coding agent"
                    "completely resolved"
                    "Do not guess or make up an answer"
                    "applicable AGENTS.md files"
                    "Fix problems at the root cause"
                    "Avoid unneeded complexity"
                    "Do not fix unrelated bugs"
                    "Run relevant tests or checks"
                    "final answer"]]
    (is (str/includes? coding/base-instructions guidance) guidance))
  (is (= coding/base-instructions
         (:instructions
          (coding/coding-agent
           {:cwd "." :approval :never :dynamic? true})))))

(deftest coding-session-preserves-conversation-between-turns
  (let [requests (atom [])
        model (k/mock-model
               (fn [request]
                 (swap! requests conj request)
                 {:type :final :output "ack"}))
        coding (coding/coding-session
                {:cwd "."
                 :approval :never
                 :model {:id "fake" :transport model}})]
    (is (= :completed (:status (coding/ask! coding "first"))))
    (is (= :completed (:status (coding/ask! coding "second"))))
    (is (= ["first" "ack" "second"]
           (mapv :content (:messages (second @requests)))))
    (is (= 4 (count (k/get-items (:session coding)))))
    (coding/clear-session! coding)
    (is (empty? (k/get-items (:session coding))))
    (is (nil? @(:last-run coding)))))

(deftest terminal-output-shows-summary-answer-and-tools
  (let [writer (StringWriter.)
        {:keys [state on-event finish]} (coding/terminal-events writer)]
    (doseq [event [{:type :model/reasoning-summary-delta
                    :depth 0
                    :delta "Inspecting."}
                   {:type :model/completed :depth 0}
                   {:type :tool/started
                    :depth 0
                    :tool "read"
                    :input {:path "README.md"}}
                   {:type :model/text-delta
                    :depth 0
                    :delta "Found it."}]]
      (on-event event))
    (finish)
    (let [rendered (str writer)]
      (is (str/includes? rendered "thinking> Inspecting."))
      (is (str/includes? rendered "tool> read README.md"))
      (is (str/includes? rendered "agent> Found it."))
      (is (:saw-text? @state)))))

(deftest terminal-approval-is-deny-by-default
  (testing "yes approves"
    (is ((coding/terminal-approval
          (BufferedReader. (StringReader. "yes\n"))
          (StringWriter.))
         {:tool {:name "bash"}
          :input {:command "clojure -M:test"}})))
  (testing "an empty answer denies"
    (is (false?
         ((coding/terminal-approval
           (BufferedReader. (StringReader. "\n"))
           (StringWriter.))
          {:tool {:name "edit"}
           :input {:path "README.md"}})))))

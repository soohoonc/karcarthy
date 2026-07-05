(ns karcarthy.cli-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [karcarthy.core :as k]
            [karcarthy.orchestrate :as o]
            [karcarthy.cli :as cli]))

(deftest json->workflow-builds-and-runs
  (testing "a JSON pipe translates to workflow data and runs on the mock runner"
    (let [workflow (cli/json->workflow {"type"  "pipe"
                                        "steps" [{"type" "agent" "name" "a" "instructions" "i"}
                                                 {"type" "agent" "name" "b" "instructions" "i"}]})]
      (is (= :pipe (:karcarthy/type workflow)))
      (is (k/agent? (first (:steps workflow))))
      (is (= "[b] [a] hi"
             (:text (o/run {:runner (k/mock-runner)
                            :workflow workflow
                            :input "hi"})))))))

(deftest json->workflow-accepts-branch-and-route
  (testing "JSON branch, reduce, and route nodes compile to runnable workflow data"
    (let [branched (cli/json->workflow {"type"     "branch"
                                        "max-concurrency" 2
                                        "branches" [{"type" "agent" "name" "a" "instructions" "i"}
                                                    {"type" "agent" "name" "b" "instructions" "i"}]})
          reduced (cli/json->workflow {"type" "reduce"
                                       "source" {"type"     "branch"
                                                 "branches" [{"type" "agent" "name" "a" "instructions" "i"}]}
                                       "reducer" {"type" "agent" "name" "r" "instructions" "i"}})
          routed  (cli/json->workflow {"type"   "route"
                                       "source" {"type" "agent" "name" "router" "instructions" "i"}
                                       "routes" {"billing" {"type" "agent" "name" "bill" "instructions" "i"}}})]
      (is (= :branch (:karcarthy/type branched)))
      (is (= 2 (:max-concurrency branched)))
      (is (= :reduce (:karcarthy/type reduced)))
      (is (= :route (:karcarthy/type routed)))
      (is (o/workflow? routed))
      (is (not (contains? routed :default)))
      (is (contains? (:routes routed) "billing")))))

(deftest json->workflow-accepts-delegate-continue-and-revise
  (testing "the rest of the preferred JSON node names compile"
    (let [delegated (cli/json->workflow {"type"            "delegate"
                                         "max-concurrency" 3
                                         "planner"         {"type" "agent" "name" "p" "instructions" "i"}
                                         "worker"          {"type" "agent" "name" "w" "instructions" "i"}})
          continued (cli/json->workflow {"type"   "continue"
                                         "source" {"type" "agent" "name" "a" "instructions" "i"}
                                         "to"     {"type" "agent" "name" "b" "instructions" "i"}})
          revised   (cli/json->workflow {"type"       "revise"
                                         "max-rounds"  4
                                         "worker"     {"type" "agent" "name" "w" "instructions" "i"}
                                         "evaluator"  {"type" "agent" "name" "j" "instructions" "i"}})]
      (is (= :delegate (:karcarthy/type delegated)))
      (is (= 3 (:max-concurrency delegated)))
      (is (= :continue (:karcarthy/type continued)))
      (is (= :revise (:karcarthy/type revised)))
      (is (= 4 (:max-rounds revised)))
      (is (every? o/workflow? [delegated continued revised])))))

(deftest json->agent-workflow-fields
  (let [a (cli/json->workflow {"type" "agent" "name" "x" "instructions" "do"
                               "model" "haiku"})]
    (is (= "x" (:name a)))
    (is (= "haiku" (:model a)))))

(deftest json->workflow-accepts-dynamic
  (let [workflow (cli/json->workflow {"type" "dynamic"
                                      "max-steps" 7
                                      "agent" {"type" "agent"
                                               "name" "workflow"
                                               "instructions" "emit EDN ops"}})]
    (is (= :dynamic (:karcarthy/type workflow)))
    (is (= 7 (:max-steps workflow)))
    (is (= "workflow" (get-in workflow [:agent :name])))
    (is (o/workflow? workflow))))

(deftest json->workflow-route-keeps-string-labels
  (testing "route labels stay strings (not coerced to keywords)"
    (let [workflow (cli/json->workflow {"type"   "route"
                                        "source" {"type" "agent" "name" "r" "instructions" "i"}
                                        "routes" {"billing" {"type" "agent" "name" "bill" "instructions" "i"}}})]
      (is (= :route (:karcarthy/type workflow)))
      (is (o/workflow? workflow))
      (is (contains? (:routes workflow) "billing")))))

(deftest json->workflow-route-accepts-default
  (testing "JSON route includes a default only when one is present"
    (let [workflow (cli/json->workflow {"type"    "route"
                                        "source"  {"type" "agent" "name" "r" "instructions" "i"}
                                        "routes"  {"billing" {"type" "agent" "name" "bill" "instructions" "i"}}
                                        "default" {"type" "agent" "name" "fallback" "instructions" "i"}})]
      (is (o/workflow? workflow))
      (is (= "fallback" (get-in workflow [:default :name]))))))

(deftest json->workflow-continue-accepts-prompt
  (testing "JSON continue includes a prompt only when one is present"
    (let [with-prompt (cli/json->workflow {"type"   "continue"
                                           "source" {"type" "agent" "name" "a" "instructions" "i"}
                                           "to"     {"type" "agent" "name" "b" "instructions" "i"}
                                           "prompt" "Now summarize."})
          without     (cli/json->workflow {"type"   "continue"
                                           "source" {"type" "agent" "name" "a" "instructions" "i"}
                                           "to"     {"type" "agent" "name" "b" "instructions" "i"}})]
      (is (o/workflow? with-prompt))
      (is (= "Now summarize." (:prompt with-prompt)))
      (is (not (contains? without :prompt))))))

(deftest json->workflow-unknown-type
  (is (thrown? clojure.lang.ExceptionInfo (cli/json->workflow {"type" "nope"}))))

(deftest json->workflow-rejects-unknown-keys
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"contains unknown options"
                        (cli/json->workflow {"type" "agent"
                                             "name" "a"
                                             "instructions" "i"
                                             "temperature" 0.2}))))

(deftest json->workflow-rejects-legacy-types
  (doseq [type ["map" "bind" "iterate"]]
    (is (thrown? clojure.lang.ExceptionInfo
                 (cli/json->workflow {"type" type})))))

(deftest cli-main-uses-named-mock-responses
  (let [request {"workflow" {"type"  "pipe"
                             "steps" [{"type" "agent" "name" "a" "instructions" "i"}
                                      {"type" "agent" "name" "b" "instructions" "i"}]}
                 "input" "hi"
                 "mock-responses" {"a" "one"
                                   "b" "two"}}
        output  (with-in-str (json/write-str request)
                  (with-out-str (cli/-main "json")))
        result  (json/read-str output)]
    (is (= true (get result "ok?")))
    (is (= "two" (get result "text")))))

(deftest cli-main-shows-help
  (let [output (with-out-str (cli/-main "--help"))]
    (is (re-find #"karcarthy agent NAME" output))
    (is (re-find #"karcarthy run WORKFLOW.json" output))))

(deftest cli-main-agent-command-prints-text
  (let [output (with-out-str
                 (cli/-main "agent" "echo"
                            "--instructions" "Echo the input."
                            "hi"))]
    (is (= "[echo] hi\n" output))))

(deftest cli-text-output-labels-failures
  (let [output (#'cli/render
                (k/result {:ok? false :error :not-accepted :text "last draft"})
                {})]
    (is (= "karcarthy failed: not-accepted\nlast draft\n" output))))

(deftest cli-main-agent-command-can-print-json
  (let [output (with-out-str
                 (cli/-main "agent" "echo"
                            "--instructions" "Echo the input."
                            "--json"
                            "hi"))
        result (json/read-str output)]
    (is (= true (get result "ok?")))
    (is (= "echo" (get result "agent")))
    (is (= "[echo] hi" (get result "text")))))

(deftest cli-main-run-command-accepts-workflow-file
  (let [file (java.io.File/createTempFile "karcarthy-workflow" ".json")]
    (try
      (spit file (json/write-str {"type" "agent"
                                  "name" "echo"
                                  "instructions" "Echo the input."}))
      (let [output (with-out-str
                     (cli/-main "run" (.getPath file) "hi"))]
        (is (= "[echo] hi\n" output)))
      (finally
        (.delete file)))))

(deftest cli-main-json-command-keeps-errors-machine-readable
  (let [output (binding [cli/*exit-on-error* false]
                 (with-in-str "{"
                   (with-out-str (cli/-main "json"))))
        result (json/read-str output)]
    (is (= false (get result "ok?")))
    (is (contains? result "error"))))

(deftest cli-failures-produce-a-nonzero-exit
  (let [request {"workflow" {"type" "revise"
                              "worker" {"type" "agent" "name" "writer" "instructions" "write"}
                              "evaluator" {"type" "agent" "name" "judge" "instructions" "judge"}
                              "max-rounds" 1}
                 "mock-responses" {"writer" "draft"
                                   "judge" "{:accept? false :feedback \"not ready\"}"}}
        execution (with-in-str (json/write-str request)
                    (#'cli/execute ["json"]))
        result (json/read-str (:out execution))]
    (is (= 1 (:exit execution)))
    (is (= false (get result "ok?")))
    (is (= "not-accepted" (get result "error")))))

(deftest cli-rejects-unknown-runners
  (let [request {"runner" "typo"
                 "workflow" {"type" "agent" "name" "a" "instructions" "i"}}
        execution (with-in-str (json/write-str request)
                    (#'cli/execute ["json"]))
        result (json/read-str (:out execution))]
    (is (= 1 (:exit execution)))
    (is (= false (get result "ok?")))
    (is (re-find #"unknown runner" (get result "error")))))

(deftest cli-json-passes-run-options
  (let [request {"workflow" {"type" "agent" "name" "a" "instructions" "i"}
                 "options" {"max-concurrency" 0}}
        execution (with-in-str (json/write-str request)
                    (#'cli/execute ["json"]))
        result (json/read-str (:out execution))]
    (is (= 1 (:exit execution)))
    (is (= false (get result "ok?")))
    (is (re-find #"max-concurrency" (get result "error")))))

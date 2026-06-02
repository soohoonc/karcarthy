(ns karcarthy.cli-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is testing]]
            [karcarthy.core :as k]
            [karcarthy.orchestrate :as o]
            [karcarthy.cli :as cli]))

(deftest json->workflow-builds-and-runs
  (testing "a JSON pipe translates to workflow data and runs on the mock adapter"
    (let [workflow (cli/json->workflow {"type"  "pipe"
                                        "steps" [{"type" "agent" "name" "a" "instructions" "i"}
                                                 {"type" "agent" "name" "b" "instructions" "i"}]})]
      (is (= :pipe (:karcarthy/type workflow)))
      (is (k/agent? (first (:steps workflow))))
      (is (= "[b] [a] hi" (:text (o/run (k/mock-adapter) workflow "hi")))))))

(deftest json->workflow-accepts-map-and-bind
  (testing "JSON map and bind names compile to runnable workflow data"
    (let [mapped (cli/json->workflow {"type"     "map"
                                      "branches" [{"type" "agent" "name" "a" "instructions" "i"}
                                                  {"type" "agent" "name" "b" "instructions" "i"}]})
          bound  (cli/json->workflow {"type"   "bind"
                                      "source" {"type" "agent" "name" "router" "instructions" "i"}
                                      "routes" {"billing" {"type" "agent" "name" "bill" "instructions" "i"}}})]
      (is (= :map (:karcarthy/type mapped)))
      (is (= :bind (:karcarthy/type bound)))
      (is (contains? (:routes bound) "billing")))))

(deftest json->agent-workflow-fields
  (let [a (cli/json->workflow {"type" "agent" "name" "x" "instructions" "do"
                               "model" "haiku" "adapter" "claude"})]
    (is (= "x" (:name a)))
    (is (= "haiku" (:model a)))
    (is (= :claude (:adapter a)))))

(deftest json->workflow-bind-keeps-string-labels
  (testing "route labels stay strings (not coerced to keywords)"
    (let [workflow (cli/json->workflow {"type"   "bind"
                                        "source" {"type" "agent" "name" "r" "instructions" "i"}
                                        "routes" {"billing" {"type" "agent" "name" "bill" "instructions" "i"}}})]
      (is (= :bind (:karcarthy/type workflow)))
      (is (contains? (:routes workflow) "billing")))))

(deftest json->workflow-unknown-type
  (is (thrown? clojure.lang.ExceptionInfo (cli/json->workflow {"type" "nope"}))))

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
  (let [output (with-in-str "{"
                 (with-out-str (cli/-main "json")))
        result (json/read-str output)]
    (is (= false (get result "ok")))
    (is (contains? result "error"))))

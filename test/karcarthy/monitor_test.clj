(ns karcarthy.monitor-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [karcarthy :as k])
  (:import [java.io StringWriter]))

(defn event [type fields]
  (merge {:karcarthy/type :event
          :type type
          :time-ms 1000
          :run-id "run_123"}
         fields))

(def start-events
  [(event :run/started {:agent "parent"})
   (event :agent/started {:agent-id "agent_parent"
                          :parent-id nil
                          :depth 0
                          :agent "parent"})
   (event :model/requested {:agent-id "agent_parent"
                            :parent-id nil
                            :depth 0})
   (event :model/completed {:agent-id "agent_parent"
                            :parent-id nil
                            :depth 0})
   (event :tool/started {:agent-id "agent_parent"
                         :parent-id nil
                         :depth 0
                         :tool "agent"
                         :tool-call-id "call_agent"})
   (event :program/read {:agent-id "agent_parent"
                         :parent-id nil
                         :depth 0
                         :source "(agent {:name \"child\" ...})"})
   (event :program/expanded {:agent-id "agent_parent"
                             :parent-id nil
                             :depth 0})
   (event :program/checked {:agent-id "agent_parent"
                            :parent-id nil
                            :depth 0})
   (event :program/evaluated {:agent-id "agent_parent"
                              :parent-id nil
                              :depth 0
                              :agent "child"})
   (event :agent/started {:agent-id "agent_child"
                          :parent-id "agent_parent"
                          :depth 1
                          :agent "child"})
   (event :model/requested {:agent-id "agent_child"
                            :parent-id "agent_parent"
                            :depth 1})])

(def finish-events
  [(event :model/completed {:agent-id "agent_child"
                            :parent-id "agent_parent"
                            :depth 1})
   (event :agent/completed {:agent-id "agent_child"
                            :parent-id "agent_parent"
                            :depth 1
                            :agent "child"})
   (event :tool/completed {:agent-id "agent_parent"
                           :parent-id nil
                           :depth 0
                           :tool "agent"
                           :tool-call-id "call_agent"})
   (event :agent/completed {:agent-id "agent_parent"
                            :parent-id nil
                            :depth 0
                            :agent "parent"})
   (event :run/completed {:agent "parent"
                          :usage {:model-calls 3}})])

(deftest monitor-projects-live-agent-tree
  (let [monitor (k/monitor)]
    (is (ifn? monitor))
    (doseq [event start-events] (monitor event))
    (let [snapshot @monitor
          run (get-in snapshot [:runs "run_123"])
          view (k/monitor-view monitor)]
      (is (= :monitor-snapshot (:karcarthy/type snapshot)))
      (is (= :running (:status run)))
      (is (= 1 (:agent-forms run)))
      (is (= ["child"] (:created-agents run)))
      (is (= "agent_parent"
             (get-in run [:agents "agent_child" :parent-id])))
      (is (str/includes? view "Run run_123 · running · 1 Agent form"))
      (is (str/includes? view "└─ parent · waiting for 1 Agent"))
      (is (str/includes? view "   └─ child · calling model")))
    (doseq [event finish-events] (monitor event))
    (let [run (get-in @monitor [:runs "run_123"])
          view (k/monitor-view monitor)]
      (is (= :completed (:status run)))
      (is (= :completed (get-in run [:agents "agent_parent" :status])))
      (is (= :completed (get-in run [:agents "agent_child" :status])))
      (is (str/includes? view "parent · done"))
      (is (str/includes? view "child · done")))))

(deftest tree-display-redraws-in-place
  (let [out (StringWriter.)
        monitor (k/monitor {:display :tree :out out})]
    (monitor (first start-events))
    (monitor (second start-events))
    (let [rendered (str out)]
      (is (str/includes? rendered "\u001b[JRun run_123 · running\n"))
      (is (str/includes? rendered "\u001b[1A\u001b[J"))
      (is (str/includes? rendered "└─ parent · running")))))

(deftest monitor-can-track-more-than-one-run
  (let [monitor (k/monitor)]
    (monitor (event :run/started {:agent "one"}))
    (monitor (assoc (event :run/started {:agent "two"}) :run-id "run_456"))
    (is (= ["run_123" "run_456"] (:run-order @monitor)))
    (is (str/includes? (k/monitor-view monitor) "Run run_456 · running"))))

(deftest monitor-validates-display-options
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #":display"
                        (k/monitor {:display :unknown})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #":out"
                        (k/monitor {:out :not-a-writer}))))

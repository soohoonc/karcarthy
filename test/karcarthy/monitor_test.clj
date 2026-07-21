(ns karcarthy.monitor-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [karcarthy :as k])
  (:import [java.io StringWriter]))

(defn event [type fields]
  (merge {:karcarthy/type :event
          :type type
          :time-ms 1000
          :run-id "run_123"}
         fields))

(defn view [monitor]
  (pr-str (k/monitor monitor)))

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
                            :depth 0
                            :usage {:input-tokens 10
                                    :output-tokens 5}})
   (event :tool/started {:agent-id "agent_parent"
                         :parent-id nil
                         :depth 0
                         :tool "eval"
                         :tool-call-id "call_eval"})
   (event :eval/started {:agent-id "agent_parent"
                         :parent-id nil
                         :depth 0
                         :code "(run! child input)"})
   (event :eval/expanded {:agent-id "agent_parent"
                          :parent-id nil
                          :depth 0})
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
                            :depth 1
                            :usage {:input-tokens 20
                                    :output-tokens 7}})
   (event :agent/completed {:agent-id "agent_child"
                            :parent-id "agent_parent"
                            :depth 1
                            :agent "child"})
   (event :tool/completed {:agent-id "agent_parent"
                           :parent-id nil
                           :depth 0
                           :tool "eval"
                           :tool-call-id "call_eval"})
   (event :agent/completed {:agent-id "agent_parent"
                            :parent-id nil
                            :depth 0
                            :agent "parent"})
   (assoc (event :run/completed {:agent "parent"
                                 :usage {:model-calls 3
                                         :input-tokens 100
                                         :output-tokens 25}})
          :time-ms 62000)])

(deftest monitor-projects-live-agent-tree
  (let [monitor (k/monitor)]
    (is (ifn? monitor))
    (doseq [event start-events] (monitor event))
    (let [snapshot @monitor
          run (get-in snapshot [:runs "run_123"])
          view (view monitor)]
      (is (= :monitor-snapshot (:karcarthy/type snapshot)))
      (is (= :running (:status run)))
      (is (= 1 (:evals run)))
      (is (= "agent_parent"
             (get-in run [:agents "agent_child" :parent-id])))
      (is (str/includes?
           view
           "Run run_123 · running · 0s · 2 model calls · 15 tokens · 1 eval"))
      (is (str/includes? view "└─ parent · waiting for Agent"))
      (is (str/includes? view "   └─ child · calling model")))
    (doseq [event finish-events] (monitor event))
    (let [run (get-in @monitor [:runs "run_123"])
          view (view monitor)]
      (is (= :completed (:status run)))
      (is (= :completed (get-in run [:agents "agent_parent" :status])))
      (is (= :completed (get-in run [:agents "agent_child" :status])))
      (is (= {:model-calls 3 :input-tokens 100 :output-tokens 25}
             (:usage run)))
      (is (str/includes? view "· completed · 1m 01s · 3 model calls · 125 tokens"))
      (is (str/includes? view "parent · done"))
      (is (str/includes? view "child · done")))))

(deftest monitor-prints-its-current-tree-at-the-repl
  (let [monitor (k/monitor)]
    (doseq [event (take 2 start-events)] (monitor event))
    (is (identical? monitor (k/monitor monitor)))
    (is (= (view monitor) (pr-str (k/monitor monitor))))
    (is (= @monitor (k/monitor-state monitor)))))

(deftest tree-display-redraws-in-place
  (let [out (StringWriter.)
        monitor (k/monitor {:display :tree :out out})]
    (try
      (monitor (first start-events))
      (monitor (second start-events))
      (let [rendered (str out)]
        (is (str/includes?
             rendered
             "\u001b[JRun run_123 · running · 0s · 0 model calls · 0 tokens\n"))
        (is (str/includes? rendered "\u001b[1A\u001b[J"))
        (is (str/includes? rendered "└─ parent · running")))
      (finally
        (.close monitor)))))

(deftest tree-display-refreshes-elapsed-time
  (let [out (StringWriter.)
        monitor (k/monitor {:display :tree :out out})
        now (System/currentTimeMillis)]
    (try
      (monitor (assoc (event :run/started {:agent "parent"}) :time-ms now))
      (Thread/sleep 1250)
      (is (re-find #"· [1-9][0-9]*s · 0 model calls"
                   (view monitor)))
      (finally
        (.close monitor)))))

(deftest monitor-can-track-more-than-one-run
  (let [monitor (k/monitor)]
    (try
      (monitor (event :run/started {:agent "one"}))
      (monitor (assoc (event :run/started {:agent "two"}) :run-id "run_456"))
      (is (= ["run_123" "run_456"] (:run-order @monitor)))
      (is (str/includes? (view monitor) "Run run_456 · running"))
      (finally
        (.close monitor)))))

(deftest monitor-validates-display-options
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #":display"
                        (k/monitor {:display :unknown})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #":out"
                        (k/monitor {:out :not-a-writer}))))

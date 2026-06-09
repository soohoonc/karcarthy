(ns karcarthy.dynamic-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [karcarthy.core :as k]
            [karcarthy.orchestrate :as o]))

(deftest text->op-reads-agent-output
  (testing "dynamic workflow replies are EDN ops, not code"
    (is (= {:op :define
            :agent {:name "writer" :instructions "write"}}
           (o/text->op
            "```edn\n{:op :define :agent {:name \"writer\" :instructions \"write\"}}\n```")))))

(deftest step-defines-patches-and-calls-agents
  (let [st      (o/state)
        runner (k/mock-runner
                 (fn [{:keys [agent prompt]}]
                   (str (:instructions agent) " :: " prompt)))]
    (o/step! runner st {:op :define
                         :agent {:name "writer"
                                 :instructions "version one"}})
    (is (= "version one :: topic"
           (:text (o/step! runner st {:op :call
                                       :agent "writer"
                                       :input "topic"}))))

    (o/step! runner st {:op :patch
                         :agent "writer"
                         :merge {:instructions "version two"}})
    (is (= "version two :: topic"
           (:text (o/step! runner st {:op :call
                                       :agent "writer"
                                       :input "topic"}))))
    (is (= 4 (count (:history (o/snapshot st)))))))

(deftest workflow-refs-resolve-at-call-time
  (testing "stored workflows pick up later agent patches"
    (let [st      (o/state :agents [(k/agent "writer" "version one")])
          runner (k/mock-runner (fn [{:keys [agent]}] (:instructions agent)))]
      (o/step! runner st {:op :define
                           :name "main"
                           :workflow (o/pipe (o/agent-ref "writer"))})
      (is (= "version one"
             (:text (o/step! runner st {:op :call
                                         :workflow "main"
                                         :input "x"}))))
      (o/step! runner st {:op :patch
                           :agent "writer"
                           :merge {:instructions "version two"}})
      (is (= "version two"
             (:text (o/step! runner st {:op :call
                                         :workflow "main"
                                         :input "x"})))))))

(deftest workflow-ref-resolves-to-named-workflow
  (let [st (o/state :agents [(k/agent "writer" "ok")]
                    :workflows {"leaf" (o/agent-ref "writer")
                                "main" (o/pipe (o/workflow-ref "leaf"))})]
    (is (= "writer" (:name (first (:steps (o/refs->workflow st (o/workflow-ref "main")))))))))

(deftest spawn-runs-one-input-per-agent-call
  (let [st      (o/state :agents [(k/agent "echo" "say")])
        runner (k/mock-runner (fn [{:keys [prompt]}] (str "got " prompt)))
        r       (o/step! runner st {:op :spawn
                                     :agent "echo"
                                     :inputs ["a" "b" "c"]})]
    (is (k/ok? r))
    (is (= ["got a" "got b" "got c"] (mapv :text (:results r))))
    (is (= "got a\n\ngot b\n\ngot c" (:text r)))))

(deftest dynamic-workflow-runs-until-complete
  (testing "the dynamic workflow agent can define, call, patch, call again, and finish"
    (let [calls  (atom 0)
          script [{:op :define
                   :agent {:name "worker"
                           :instructions "version one"}}
                  {:op :define
                   :name "main"
                   :workflow (o/pipe (o/agent-ref "worker"))}
                  {:op :call
                   :workflow "main"
                   :input "topic"}
                  {:op :patch
                   :agent "worker"
                   :merge {:instructions "version two"}}
                  {:op :call
                   :workflow "main"
                   :input "topic"}
                  {:op :complete
                   :text "done"}]
          runner (k/mock-runner
                   (fn [{:keys [agent]}]
                     (if (= "workflow" (:name agent))
                       (pr-str (nth script (dec (swap! calls inc))))
                       (:instructions agent))))
          workflow-agent (k/agent "workflow" "Drive the workflow with EDN ops.")
          r (o/run runner (o/dynamic workflow-agent :max-steps 10) "build a worker")]
      (is (k/ok? r))
      (is (= "done" (:text r)))
      (is (= 6 (:steps r)))
      (is (= "version two" (get-in r [:state :agents "worker" :instructions])))
      (is (= "version two" (-> r :state :history (nth 4) :result :text))))))

(deftest dynamic-workflow-reports-bad-op
  (let [runner       (k/mock-runner (fn [_] "{:op :bogus}"))
        workflow-agent (k/agent "workflow" "bad op")
        r             (o/run runner (o/dynamic workflow-agent :max-steps 1) "x")]
    (is (not (k/ok? r)))
    (is (str/includes? (:error r) "unknown dynamic workflow op"))
    (is (map? (:state r)))))

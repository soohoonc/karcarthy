(ns karcarthy.dynamic-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [karcarthy.core :as k]
            [karcarthy.dynamic :as dyn]
            [karcarthy.orchestrate :as o]))

(deftest text->op-reads-agent-output
  (testing "dynamic workflow replies are EDN ops, not code"
    (is (= {:op :define
            :agent {:name "writer" :instructions "write"}}
           (dyn/text->op
            "```edn\n{:op :define :agent {:name \"writer\" :instructions \"write\"}}\n```")))))

(deftest step-defines-patches-and-calls-agents
  (let [st      (dyn/state)
        runner (k/mock-runner
                 (fn [{:keys [agent prompt]}]
                   (str (:instructions agent) " :: " prompt)))]
    (dyn/step! runner st {:op :define
                         :agent {:name "writer"
                                 :description "Draft writer"
                                 :instructions "version one"
                                 :model "sonnet"
                                 :tools ["Read"]
                                 :config {:temperature 0.2}}})
    (is (= {:description "Draft writer"
            :model "sonnet"
            :tools ["Read"]
            :config {:temperature 0.2}}
           (select-keys (get-in (dyn/snapshot st) [:agents "writer"])
                        [:description :model :tools :config])))
    (is (= "version one :: topic"
           (:text (dyn/step! runner st {:op :call
                                       :agent "writer"
                                       :input "topic"}))))

    (dyn/step! runner st {:op :patch
                         :agent "writer"
                         :merge {:instructions "version two"}})
    (is (= "version two :: topic"
           (:text (dyn/step! runner st {:op :call
                                       :agent "writer"
                                       :input "topic"}))))
    (is (= 4 (count (:history (dyn/snapshot st)))))))

(deftest workflow-refs-resolve-at-call-time
  (testing "stored workflows pick up later agent patches"
    (let [st      (dyn/state :agents [(k/agent {:name "writer"
                                              :instructions "version one"})])
          runner (k/mock-runner (fn [{:keys [agent]}] (:instructions agent)))]
      (dyn/step! runner st {:op :define
                           :name "main"
                           :workflow (o/pipe (dyn/agent-ref "writer"))})
      (is (= "version one"
             (:text (dyn/step! runner st {:op :call
                                         :workflow "main"
                                         :input "x"}))))
      (dyn/step! runner st {:op :patch
                           :agent "writer"
                           :merge {:instructions "version two"}})
      (is (= "version two"
             (:text (dyn/step! runner st {:op :call
                                         :workflow "main"
                                         :input "x"})))))))

(deftest workflow-ref-resolves-to-named-workflow
  (let [st (dyn/state :agents [(k/agent {:name "writer" :instructions "ok"})]
                    :workflows {"leaf" (dyn/agent-ref "writer")
                                "main" (o/pipe (dyn/workflow-ref "leaf"))})]
    (is (= "writer" (:name (first (:steps (dyn/refs->workflow st (dyn/workflow-ref "main")))))))))

(deftest spawn-runs-one-input-per-agent-call
  (let [st      (dyn/state :agents [(k/agent {:name "echo" :instructions "say"})])
        runner (k/mock-runner (fn [{:keys [prompt]}] (str "got " prompt)))
        r       (dyn/step! runner st {:op :spawn
                                     :agent "echo"
                                     :inputs ["a" "b" "c"]})]
    (is (k/ok? r))
    (is (= ["got a" "got b" "got c"] (mapv :text (:results r))))
    (is (= "got a\n\ngot b\n\ngot c" (:text r)))))

(deftest spawn-renders-structured-inputs
  (let [st      (dyn/state :agents [(k/agent {:name "echo" :instructions "say"})])
        runner (k/mock-runner (fn [{:keys [prompt]}] prompt))
        r       (dyn/step! runner st {:op :spawn
                                     :agent "echo"
                                     :inputs [{:prompt "review"
                                               :ticket 42}
                                              {:topic "billing"}]})]
    (is (k/ok? r))
    (is (= ["review\n\nINPUT EDN:\n{:ticket 42}"
            "{:topic \"billing\"}"]
           (mapv :text (:results r))))))

(deftest dynamic-workflow-runs-until-complete
  (testing "the dynamic workflow agent can define, call, patch, call again, and finish"
    (let [calls  (atom 0)
          script [{:op :define
                   :agent {:name "worker"
                           :instructions "version one"}}
                  {:op :define
                   :name "main"
                   :workflow (o/pipe (dyn/agent-ref "worker"))}
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
          workflow-agent (k/agent {:name "workflow"
                                   :instructions "Drive the workflow with EDN ops."})
          r (o/run {:runner runner
                    :workflow (dyn/dynamic workflow-agent :max-steps 10)
                    :input "build a worker"})]
      (is (k/ok? r))
      (is (= "done" (:text r)))
      (is (= 6 (:steps r)))
      (is (= "version two" (get-in r [:state :agents "worker" :instructions])))
      (is (= "version two" (-> r :state :history (nth 4) :result :text))))))

(deftest dynamic-workflow-feeds-back-bad-ops-then-aborts
  (testing "an invalid op is retried once via feedback, then aborts the run"
    (let [calls          (atom 0)
          runner         (k/mock-runner (fn [_] (swap! calls inc) "{:op :bogus}"))
          workflow-agent (k/agent {:name "workflow" :instructions "bad op"})
          r              (o/run {:runner runner
                                 :workflow (dyn/dynamic workflow-agent :max-steps 10)
                                 :input "x"})]
      (is (not (k/ok? r)))
      (is (str/includes? (:error r) "unknown dynamic workflow op"))
      (is (= 2 @calls))
      (is (map? (:state r))))))

(deftest dynamic-workflow-recovers-from-bad-op
  (testing "the op error is fed back as the last result, so the agent can recover"
    (let [prompts        (atom [])
          runner         (k/mock-runner
                          (fn [{:keys [prompt]}]
                            (swap! prompts conj prompt)
                            (if (= 1 (count @prompts))
                              "this is not an op"
                              (pr-str {:op :complete :text "recovered"}))))
          workflow-agent (k/agent {:name "workflow" :instructions "drive"})
          r              (o/run {:runner runner
                                 :workflow (dyn/dynamic workflow-agent :max-steps 10)
                                 :input "x"})]
      (is (k/ok? r))
      (is (= "recovered" (:text r)))
      (is (str/includes? (second @prompts) "no EDN map found")))))

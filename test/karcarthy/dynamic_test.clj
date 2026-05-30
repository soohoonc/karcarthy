(ns karcarthy.dynamic-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [karcarthy.core :as k]
            [karcarthy.dynamic :as dyn]
            [karcarthy.orchestrate :as o]))

(deftest read-operation-accepts-edn
  (testing "controller replies are parsed as operation data"
    (is (= :define-agent
           (:karcarthy/op
            (dyn/read-operation
             "```edn\n{:op :define-agent :agent {:karcarthy/type :agent :name \"a\" :instructions \"i\"}}\n```"))))))

(deftest define-and-run-agent
  (testing "a dynamic operation can define an agent, then run it"
    (let [rt     (dyn/dynamic-runtime)
          runner (k/mock-runner (fn [{:keys [agent prompt]}]
                                  (str (:instructions agent) " :: " prompt)))]
      (dyn/apply-operation runner rt {:karcarthy/op :define-agent
                                      :agent (k/agent "writer" "version one")})
      (let [r (dyn/apply-operation runner rt {:karcarthy/op :run-agent
                                              :name "writer"
                                              :input "topic"})]
        (is (k/ok? r))
        (is (= "version one :: topic" (:text r)))))))

(deftest put-patch-call-and-remove-agent
  (testing "new operations mutate agent state directly"
    (let [rt     (dyn/dynamic-runtime)
          runner (k/mock-runner (fn [{:keys [agent prompt]}]
                                  (str (:instructions agent) " :: " prompt)))]
      (dyn/apply-operation runner rt {:karcarthy/op :put
                                      :resource {:kind :agent
                                                 :id "writer"
                                                 :instructions "version one"}})
      (is (= "version one :: topic"
             (:text (dyn/apply-operation runner rt {:karcarthy/op :call
                                                    :target "writer"
                                                    :input "topic"}))))

      (dyn/apply-operation runner rt {:karcarthy/op :patch
                                      :kind :agent
                                      :id "writer"
                                      :merge {:instructions "version two"}})
      (is (= "version two :: topic"
             (:text (dyn/apply-operation runner rt {:karcarthy/op :call
                                                    :target "writer"
                                                    :input "topic"}))))

      (dyn/apply-operation runner rt {:karcarthy/op :remove
                                      :kind :agent
                                      :id "writer"})
      (is (nil? (get-in (dyn/snapshot rt) [:agents "writer"]))))))

(deftest put-workflow-and-call-it
  (testing "workflows are living state that resolve agent refs at call time"
    (let [rt     (dyn/dynamic-runtime)
          runner (k/mock-runner (fn [{:keys [agent]}] (:instructions agent)))]
      (dyn/apply-operation runner rt {:karcarthy/op :put
                                      :resource {:kind :agent
                                                 :id "writer"
                                                 :instructions "graph v1"}})
      (dyn/apply-operation runner rt {:karcarthy/op :put
                                      :resource {:kind :workflow
                                                 :id "main"
                                                 :workflow (o/chain (dyn/dynamic-agent-ref "writer"))}})
      (is (= "graph v1"
             (:text (dyn/apply-operation runner rt {:karcarthy/op :call
                                                    :target "main"
                                                    :input "x"}))))
      (dyn/apply-operation runner rt {:karcarthy/op :patch
                                      :kind :agent
                                      :id "writer"
                                      :merge {:instructions "graph v2"}})
      (is (= "graph v2"
             (:text (dyn/apply-operation runner rt {:karcarthy/op :call
                                                    :kind :workflow
                                                    :target "main"
                                                    :input "x"})))))))

(deftest call-supports-simple-fanout
  (testing "a call can fan out over a collection of inputs"
    (let [rt     (dyn/dynamic-runtime :agents [(k/agent "echo" "say")])
          runner (k/mock-runner (fn [{:keys [prompt]}] (str "got " prompt)))
          r      (dyn/apply-operation runner rt {:karcarthy/op :call
                                                 :target "echo"
                                                 :for-each ["a" "b" "c"]})]
      (is (k/ok? r))
      (is (= ["got a" "got b" "got c"] (mapv :text (:results r))))
      (is (= "got a\n\ngot b\n\ngot c" (:text r))))))

(deftest workflow-refs-resolve-late
  (testing "stored workflows resolve agent refs at run time, so patches affect later runs"
    (let [rt     (dyn/dynamic-runtime :agents [(k/agent "writer" "version one")])
          runner (k/mock-runner (fn [{:keys [agent]}] (:instructions agent)))
          flow   (o/chain (dyn/dynamic-agent-ref "writer"))]
      (dyn/apply-operation runner rt {:karcarthy/op :define-workflow
                                      :name "main"
                                      :workflow flow})
      (is (= "version one"
             (:text (dyn/apply-operation runner rt {:karcarthy/op :run-workflow
                                                    :name "main"
                                                    :input "x"}))))
      (dyn/apply-operation runner rt {:karcarthy/op :patch-agent
                                      :name "writer"
                                      :patch {:instructions "version two"}})
      (is (= "version two"
             (:text (dyn/apply-operation runner rt {:karcarthy/op :run-workflow
                                                    :name "main"
                                                    :input "x"})))))))

(deftest materialize-workflow-ref
  (testing "workflow refs are recursively resolved"
    (let [rt (dyn/dynamic-runtime
              :agents [(k/agent "a" "i")]
              :workflows {"leaf" (dyn/dynamic-agent-ref "a")
                          "main" (o/chain (dyn/dynamic-workflow-ref "leaf"))})]
      (is (k/agent? (first (:steps (dyn/materialize rt (dyn/dynamic-workflow-ref "main")))))))))

(deftest run-dynamic-controller-loop
  (testing "a controller can grow and patch the agent/workflow universe as data"
    (let [calls  (atom 0)
          script [{:karcarthy/op :define-agent
                   :agent {:karcarthy/type :agent
                           :name "worker"
                           :instructions "version one"}}
                  {:karcarthy/op :define-workflow
                   :name "main"
                   :workflow {:karcarthy/type :chain
                              :steps [{:karcarthy/type :agent-ref
                                       :name "worker"}]}}
                  {:karcarthy/op :run-workflow
                   :name "main"
                   :input "topic"}
                  {:karcarthy/op :patch-agent
                   :name "worker"
                   :patch {:instructions "version two"}}
                  {:karcarthy/op :run-workflow
                   :name "main"
                   :input "topic"}
                  {:karcarthy/op :answer
                   :text "done"}]
          runner (k/mock-runner
                  (fn [{:keys [agent]}]
                    (if (= "controller" (:name agent))
                      (pr-str (nth script (dec (swap! calls inc)) nil))
                      (:instructions agent))))
          controller (k/agent "controller" "Emit dynamic karcarthy operations.")
          r (dyn/run-dynamic runner controller "build and improve a worker"
                             :max-steps 10)]
      (is (k/ok? r))
      (is (= "done" (:text r)))
      (is (= "version two" (get-in r [:runtime :agents "worker" :instructions])))
      (is (= "version two" (-> r :runtime :history (nth 4) :result :text)))
      (is (= 6 (:steps r))))))

(deftest run-dynamic-controller-loop-with-living-ops
  (testing "a controller can self-evolve with put, patch, call, and complete"
    (let [calls  (atom 0)
          script [{:karcarthy/op :put
                   :resource {:kind :agent
                              :id "worker"
                              :instructions "version one"}}
                  {:karcarthy/op :call
                   :target "worker"
                   :input "topic"}
                  {:karcarthy/op :patch
                   :kind :agent
                   :id "worker"
                   :merge {:instructions "version two"}}
                  {:karcarthy/op :call
                   :target "worker"
                   :input "topic"}
                  {:karcarthy/op :complete
                   :text "done"}]
          runner (k/mock-runner
                  (fn [{:keys [agent]}]
                    (if (= "controller" (:name agent))
                      (pr-str (nth script (dec (swap! calls inc)) nil))
                      (:instructions agent))))
          controller (k/agent "controller" "Emit dynamic karcarthy operations.")
          r (dyn/run-dynamic runner controller "build and improve a worker"
                             :max-steps 10)]
      (is (k/ok? r))
      (is (= "done" (:text r)))
      (is (= "version two" (get-in r [:runtime :agents "worker" :instructions])))
      (is (= "version two" (-> r :runtime :history (nth 3) :result :text)))
      (is (= 5 (:steps r))))))

(deftest run-dynamic-reports-bad-operation
  (testing "bad controller output returns a not-ok result with state"
    (let [runner (k/mock-runner (fn [{:keys [agent]}]
                                  (if (= "controller" (:name agent))
                                    "{:karcarthy/op :unknown}"
                                    "unused")))
          r (dyn/run-dynamic runner
                             (k/agent "controller" "Emit bad op.")
                             "x"
                             :max-steps 1)]
      (is (not (k/ok? r)))
      (is (str/includes? (:error r) "unknown dynamic operation"))
      (is (map? (:runtime r))))))

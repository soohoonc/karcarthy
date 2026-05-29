(ns karcarthy.self-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [karcarthy.core :as k]
            [karcarthy.orchestrate :as o]
            [karcarthy.self :as self]))

;; --- extract-edn -----------------------------------------------------------

(deftest extract-edn-plain
  (is (= {:karcarthy/type :agent :name "a" :instructions "i"}
         (self/extract-edn "{:karcarthy/type :agent :name \"a\" :instructions \"i\"}"))))

(deftest extract-edn-fenced
  (testing "pulls EDN out of a ```edn code fence"
    (is (= {:karcarthy/type :agent :name "a" :instructions "i"}
           (self/extract-edn "Sure!\n```edn\n{:karcarthy/type :agent :name \"a\" :instructions \"i\"}\n```\nDone.")))))

(deftest extract-edn-prose-prefix
  (testing "finds the first map after prose"
    (is (= {:x 1} (self/extract-edn "Here you go: {:x 1} hope that helps")))))

(deftest extract-edn-none
  (is (thrown? clojure.lang.ExceptionInfo (self/extract-edn "no edn here"))))

;; --- read-workflow / read-agent --------------------------------------------

(deftest read-workflow-agent
  (let [workflow (self/read-workflow "{:karcarthy/type :agent :name \"w\" :instructions \"do\"}")]
    (is (k/agent? workflow))
    (is (= "w" (:name workflow)))))

(deftest read-workflow-nested-node
  (let [workflow (self/read-workflow (str "{:karcarthy/type :chain :steps ["
                                          "{:karcarthy/type :agent :name \"a\" :instructions \"i\"} "
                                          "{:karcarthy/type :agent :name \"b\" :instructions \"i\"}]}"))]
    (is (o/workflow? workflow))
    (is (= :chain (:karcarthy/type workflow)))))

(deftest read-workflow-rejects-non-workflow
  (is (thrown? clojure.lang.ExceptionInfo (self/read-workflow "{:foo 1}"))))

(deftest read-workflow-rejects-bad-nested-agent
  (testing "an invalid nested agent (blank name) is caught"
    (is (thrown? clojure.lang.ExceptionInfo
                 (self/read-workflow (str "{:karcarthy/type :chain :steps ["
                                          "{:karcarthy/type :agent :name \"\" :instructions \"i\"}]}"))))))

(deftest read-flow-compatibility-alias
  (let [workflow (self/read-flow "{:karcarthy/type :agent :name \"w\" :instructions \"do\"}")]
    (is (k/agent? workflow))
    (is (= "w" (:name workflow)))))

;; --- run-authored: an agent writes a workflow, then it runs ----------------

(deftest run-authored-builds-and-runs
  (testing "the author writes a workflow as EDN; karcarthy parses and runs it"
    (let [h (k/mock-runner
             (fn [{:keys [agent prompt]}]
               (if (= "author" (:name agent))
                 "```edn\n{:karcarthy/type :agent :name \"worker\" :instructions \"do the task\"}\n```"
                 (str "[" (:name agent) "] " prompt))))
          {:keys [workflow flow result]} (self/run-authored h
                                                            (k/agent "author" "You design workflows.")
                                                            "summarize the doc")]
      (is (k/agent? workflow))
      (is (= workflow flow))
      (is (= "worker" (:name workflow)))
      (is (= "[worker] summarize the doc" (:text result))))))

(deftest run-authored-builds-a-pipeline
  (testing "the author can write a multi-step workflow"
    (let [h (k/mock-runner
             (fn [{:keys [agent prompt]}]
               (if (= "author" (:name agent))
                 (str "{:karcarthy/type :chain :steps ["
                      "{:karcarthy/type :agent :name \"x\" :instructions \"i\"} "
                      "{:karcarthy/type :agent :name \"y\" :instructions \"i\"}]}")
                 (str "[" (:name agent) "] " prompt))))
          {:keys [workflow result]} (self/run-authored h (k/agent "author" "design") "task")]
      (is (= :chain (:karcarthy/type workflow)))
      (is (= "[y] [x] task" (:text result))))))

;; --- evolve: an agent edits its own definition at runtime ------------------

(deftest evolve-self-modifies-then-answers
  (testing "the agent patches its own instructions, then answers with new behavior"
    (let [h (k/mock-runner
             (fn [{:keys [agent]}]
               ;; until it has 'EVOLVED' instructions, it asks to patch itself
               (if (str/includes? (:instructions agent) "EVOLVED")
                 "final answer"
                 "{:karcarthy/patch {:instructions \"EVOLVED instructions\"} :reason \"better\"}")))
          r (o/run h (self/evolve (k/agent "self" "original instructions")) "do X")]
      (is (k/ok? r))
      (is (= "final answer" (:text r)))
      (is (= 2 (:rounds r)))
      (is (= 1 (count (:patches r))))
      (is (str/includes? (:instructions (:evolved r)) "EVOLVED")))))

(deftest evolve-stops-at-max-rounds
  (testing "an agent that always patches is capped, then forced to a final run"
    (let [calls (atom 0)
          h (k/mock-runner
             (fn [_]
               (swap! calls inc)
               ;; always returns a patch -> should hit max-rounds and force-finish
               "{:karcarthy/patch {:instructions \"again\"} :reason \"loop\"}"))
          r (o/run h (self/evolve (k/agent "loop" "i") :max-rounds 3) "x")]
      (is (= 3 (:rounds r)))
      ;; 3 evolve rounds + 1 forced final plain run
      (is (= 4 @calls)))))

(deftest evolve-no-change-passes-through
  (testing "if the agent answers immediately, no patches are applied"
    (let [h (k/mock-runner (fn [_] "immediate answer"))
          r (o/run h (self/evolve (k/agent "a" "i")) "x")]
      (is (= "immediate answer" (:text r)))
      (is (= 1 (:rounds r)))
      (is (empty? (:patches r))))))

;; --- registry: edit a named agent's behavior at runtime --------------------

(deftest agent-ref-resolves-at-runtime
  (testing "patching a registered agent changes what agent-ref runs next"
    (let [reg (self/registry [(k/agent "writer" "version one")])
          ;; this runner echoes the agent's *current* instructions
          h   (k/mock-runner (fn [{:keys [agent]}] (:instructions agent)))
          ref (self/agent-ref reg "writer")]
      (is (= "version one" (:text (o/run h ref "x"))))
      (self/patch-agent! reg "writer" {:instructions "version two"})
      (is (= "version two" (:text (o/run h ref "x")))))))

(deftest agent-ref-unknown
  (let [reg (self/registry [])
        r   (o/run (k/mock-runner) (self/agent-ref reg "missing") "x")]
    (is (not (k/ok? r)))
    (is (= :unknown-agent (:error r)))))

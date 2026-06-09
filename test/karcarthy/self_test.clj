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

(deftest read-agent-workflow
  (let [workflow (self/read-workflow "{:karcarthy/type :agent :name \"w\" :instructions \"do\"}")]
    (is (k/agent? workflow))
    (is (= "w" (:name workflow)))))

(deftest read-workflow-nested-node
  (let [workflow (self/read-workflow (str "{:karcarthy/type :pipe :steps ["
                                          "{:karcarthy/type :agent :name \"a\" :instructions \"i\"} "
                                          "{:karcarthy/type :agent :name \"b\" :instructions \"i\"}]}"))]
    (is (o/workflow? workflow))
    (is (= :pipe (:karcarthy/type workflow)))))

(deftest read-workflow-rejects-non-workflow
  (is (thrown? clojure.lang.ExceptionInfo (self/read-workflow "{:foo 1}"))))

(deftest read-workflow-rejects-bad-nested-agent
  (testing "an invalid nested agent (blank name) is caught"
    (is (thrown? clojure.lang.ExceptionInfo
                 (self/read-workflow (str "{:karcarthy/type :pipe :steps ["
                                          "{:karcarthy/type :agent :name \"\" :instructions \"i\"}]}"))))))

;; --- evolve: an agent edits its own definition at runtime ------------------

(deftest evolve-workflow-predicate-validates-extension-data
  (testing "extension nodes must validate their own workflow data"
    (is (o/workflow? (self/evolve (k/agent "self" "i"))))
    (is (not (o/workflow? (assoc (self/evolve (k/agent "self" "i"))
                                  :host/fn
                                  (fn [] :x)))))
    (is (not (o/workflow? {:karcarthy/type :evolve
                           :agent (fn [_] :x)
                           :max-rounds 5})))
    (is (not (o/workflow? {:karcarthy/type :evolve
                           :agent (k/agent "self" "i")
                           :max-rounds 0})))))

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

(deftest evolve-rejects-unknown-patch-keys
  (testing "self-modification cannot smuggle arbitrary agent fields"
    (let [h (k/mock-runner
             (fn [_] "{:karcarthy/patch {:name \"renamed\"} :reason \"bad\"}"))
          r (o/run h (self/evolve (k/agent "a" "i")) "x")]
      (is (not (k/ok? r)))
      (is (= :invalid-patch (:error r)))
      (is (str/includes? (:text r) "unknown keys")))))

(deftest evolve-rejects-invalid-patch-values
  (testing "a patch must still produce a valid agent"
    (let [h (k/mock-runner
             (fn [_] "{:karcarthy/patch {:tools [42]} :reason \"bad\"}"))
          r (o/run h (self/evolve (k/agent "a" "i")) "x")]
      (is (not (k/ok? r)))
      (is (= :invalid-patch (:error r)))
      (is (str/includes? (:text r) ":tools must be a vector of strings")))))

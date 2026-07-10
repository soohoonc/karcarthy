(ns karcarthy.examples-test
  (:require [clojure.test :refer [deftest is]]
            [karcarthy.examples.dynamic :as dynamic]
            [karcarthy.examples.hello :as hello])
  (:import [java.nio.file Files Path]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-directory []
  (Files/createTempDirectory "karcarthy-dynamic-demo-"
                             (make-array FileAttribute 0)))

(defn- delete-tree! [^Path root]
  (doseq [file (reverse (file-seq (.toFile root)))]
    (Files/deleteIfExists (.toPath file))))

(deftest offline-hello-calls-the-tool-loop
  (is (= "HELLO\n" (with-out-str (hello/-main "hello")))))

(deftest model-submits-and-runs-a-new-agent-form
  (let [root (temp-directory)]
    (try
      (let [trial (dynamic/run-candidate!
                   :target-parser
                   {:id "proof"
                    :prompt "Write the target.\nTARGET=proof_42"
                    :expected "proof_42"}
                   root)]
        (is (:passed? trial))
        (is (= ["architect-target-parser" "generated-target-parser"]
               (:agents trial)))
        (is (= 1 (:agent-forms trial)))
        (is (= #{":program/read" ":program/expanded" ":program/checked"
                 ":program/evaluated"}
               (set (:program-events trial)))))
      (finally
        (delete-tree! root)))))

(deftest metric-search-selects-the-best-generated-program
  (let [root (temp-directory)]
    (try
      (let [result (dynamic/hill-climb! root)
            scores (into {} (map (juxt :strategy :passed))
                         (:candidates result))]
        (is (= {"constant" 1 "first-line" 0 "target-parser" 3}
               scores))
        (is (= "target-parser" (:winner result)))
        (is (= 1.0 (:score result))))
      (finally
        (delete-tree! root)))))

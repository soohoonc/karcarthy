(ns karcarthy.proc-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [karcarthy.proc :as proc])
  (:import [java.lang ProcessHandle]))

(deftest captures-stdout-and-exit
  (let [{:keys [exit out timed-out?]} (proc/run ["echo" "hello"] {})]
    (is (= 0 exit))
    (is (false? timed-out?))
    (is (= "hello\n" out))))

(deftest pipes-stdin
  (testing ":in is written to the process stdin"
    (is (= "piped input" (:out (proc/run ["cat"] {:in "piped input"}))))))

(deftest nonzero-exit
  (is (= 1 (:exit (proc/run ["false"] {})))))

(deftest timeout-kills-process
  (testing "a long process is force-killed at :timeout-ms and returns promptly"
    (let [start   (System/currentTimeMillis)
          {:keys [timed-out? exit]} (proc/run ["sleep" "10"] {:timeout-ms 300})
          elapsed (- (System/currentTimeMillis) start)]
      (is (true? timed-out?))
      (is (nil? exit))
      (is (< elapsed 5000) "must not wait for the full sleep"))))

(deftest timeout-includes-stdin-writing
  (testing "a child that never reads a large stdin cannot evade the timeout"
    (let [input (apply str (repeat (* 1024 1024) "x"))
          start (System/currentTimeMillis)
          result (proc/run ["sleep" "10"] {:in input :timeout-ms 150})
          elapsed (- (System/currentTimeMillis) start)]
      (is (true? (:timed-out? result)))
      (is (< elapsed 3000)))))

(deftest timeout-kills-descendant-processes
  (let [pid-file (java.io.File/createTempFile "karcarthy-child" ".pid")]
    (try
      (.delete pid-file)
      (let [script (str "sleep 10 & echo $! > " (.getPath pid-file) "; wait")
            result (proc/run ["sh" "-c" script] {:timeout-ms 300})]
        (is (true? (:timed-out? result)))
        (is (.exists pid-file))
        (let [pid (Long/parseLong (str/trim (slurp pid-file)))
              handle (ProcessHandle/of pid)]
          (is (or (not (.isPresent handle))
                  (not (.isAlive (.get handle)))))))
      (finally (.delete pid-file)))))

(deftest env-merged-over-inherited
  (testing ":env vars are visible to the process"
    (is (= "xyz\n" (:out (proc/run ["sh" "-c" "echo $KARC_TEST"]
                                   {:env {"KARC_TEST" "xyz"}}))))))

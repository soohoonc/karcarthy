(ns karcarthy.proc-test
  (:require [clojure.test :refer [deftest is testing]]
            [karcarthy.proc :as proc]))

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

(deftest env-merged-over-inherited
  (testing ":env vars are visible to the process"
    (is (= "xyz\n" (:out (proc/run ["sh" "-c" "echo $KARC_TEST"]
                                   {:env {"KARC_TEST" "xyz"}}))))))

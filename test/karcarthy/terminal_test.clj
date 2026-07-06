(ns karcarthy.terminal-test
  (:require [clojure.test :refer [deftest is]]
            [karcarthy.terminal :as terminal]))

(def ^:private cwd
  (.getAbsolutePath (java.io.File. ".")))

(deftest terminal-lifecycle-captures-bounded-utf8-output
  (let [service (terminal/service)
        {:keys [terminalId]}
        (terminal/handle! service "terminal/create"
                          {:command "sh"
                           :args ["-c" "printf 'αβγ'"]
                           :cwd cwd
                           :outputByteLimit 4})]
    (try
      (is (= {:exitCode 0 :signal nil}
             (terminal/handle! service "terminal/wait_for_exit"
                               {:terminalId terminalId})))
      (let [result (terminal/handle! service "terminal/output"
                                     {:terminalId terminalId})]
        (is (= "βγ" (:output result)))
        (is (true? (:truncated result)))
        (is (= 0 (get-in result [:exitStatus :exitCode]))))
      (is (= {} (terminal/handle! service "terminal/release"
                                  {:terminalId terminalId})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"unknown ACP terminal"
                            (terminal/handle! service "terminal/output"
                                              {:terminalId terminalId})))
      (finally
        (terminal/close! service)))))

(deftest terminal-captures-environment-stdout-and-stderr
  (let [service (terminal/service)
        {:keys [terminalId]}
        (terminal/handle! service "terminal/create"
                          {:command "sh"
                           :args ["-c" "printf \"$KARCARTHY_TERM\"; printf err >&2"]
                           :env [{:name "KARCARTHY_TERM" :value "value"}]
                           :cwd cwd})]
    (try
      (terminal/handle! service "terminal/wait_for_exit" {:terminalId terminalId})
      (is (= "valueerr"
             (:output (terminal/handle! service "terminal/output"
                                        {:terminalId terminalId}))))
      (finally
        (terminal/close! service)))))

(deftest close-kills-unreleased-processes
  (let [service (terminal/service)
        {:keys [terminalId]}
        (terminal/handle! service "terminal/create"
                          {:command "sh" :args ["-c" "sleep 10"] :cwd cwd})
        process (:process (get @service terminalId))]
    (terminal/close! service)
    (is (not (.isAlive ^Process process)))
    (is (empty? @service))))

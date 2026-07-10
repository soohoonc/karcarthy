(ns karcarthy.cli-test
  (:require [clojure.test :refer [deftest is]]
            [karcarthy.cli :as cli]))

(deftest help-is-available
  (is (re-find #"native Clojure agent harness"
               (with-out-str (cli/-main "--help")))))

(deftest offline-demo-command
  (is (= "HELLO\n" (with-out-str (cli/-main "demo" "hello")))))

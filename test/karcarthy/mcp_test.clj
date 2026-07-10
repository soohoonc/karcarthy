(ns karcarthy.mcp-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [karcarthy.mcp :as mcp]))

(defn- absolute-classpath []
  (->> (str/split (System/getProperty "java.class.path")
                  (re-pattern (java.util.regex.Pattern/quote
                               java.io.File/pathSeparator)))
       (map #(.getAbsolutePath (java.io.File. %)))
       (str/join java.io.File/pathSeparator)))

(defn fixture-command []
  {:name "fixture"
   :command (str (System/getProperty "java.home") "/bin/java")
   :args ["-cp" (absolute-classpath)
          "clojure.main" "-m" "karcarthy.mcp-fixture"]})

(deftest discovers-and-adapts-stdio-tools
  (let [connection (mcp/connect! (fixture-command))]
    (try
      (is (mcp/connection? connection))
      (is (= ["echo"] (mapv :name (mcp/definitions connection))))
      (let [tool (first (mcp/tools connection {:approval :never}))
            output ((:execute tool) nil {:text "hello"})]
        (is (= "mcp_fixture__echo" (:name tool)))
        (is (= {:echo "hello"} (:structured_content output)))
        (is (false? (:is_error output))))
      (finally
        (is (true? (mcp/close! connection)))))))

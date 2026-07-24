(ns karcarthy.tools-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [karcarthy :as k]
            [karcarthy.tools :as tools])
  (:import [java.lang ProcessHandle]
           [java.nio.file Files Path]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-directory []
  (Files/createTempDirectory "karcarthy-coding-"
                             (make-array FileAttribute 0)))

(defn- delete-tree! [^Path root]
  (doseq [file (reverse (file-seq (.toFile root)))]
    (Files/deleteIfExists (.toPath file))))

(defn- by-name [tools name]
  (first (filter #(= name (:name %)) tools)))

(deftest minimal-local-tools-work-together
  (let [root (temp-directory)]
    (try
      (let [tools (tools/local {:cwd (str root)})
            read (by-name tools "read")
            write (by-name tools "write")
            edit (by-name tools "edit")
            bash (by-name tools "bash")
            search (by-name tools "search")]
        (is (= ["read" "write" "edit" "bash" "search"]
               (mapv :name tools)))
        (is (= {:path "src/example.txt" :bytes 12}
               ((:execute write) nil
                {:path "src/example.txt" :content "hello world\n"})))
        (is (= "hello world"
               (:content ((:execute read) nil
                          {:path "src/example.txt"}))))
        (is (= 1
               (:replacements
                ((:execute edit) nil
                 {:path "src/example.txt"
                  :old_text "world"
                  :new_text "karcarthy"}))))
        (is (re-find #"karcarthy"
                     (:output ((:execute search) nil
                               {:pattern "karcarthy"
                                :path "src"
                                :max_results 1}))))
        (is (= 1
               (:matches ((:execute search) nil
                          {:pattern "karcarthy"
                           :path "src"
                           :max_results 1}))))
        (is (= "src/example.txt"
               (clojure.string/trim
                (:output ((:execute bash) nil
                          {:command "find src -type f"})))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"escapes"
                              ((:execute read) nil {:path "../secret"}))))
      (finally
        (delete-tree! root)))))

(deftest prompts-compose-with-tools-without-a-workspace-abstraction
  (let [root (temp-directory)]
    (try
      (spit (.toFile (.resolve root "AGENTS.md"))
            "Run the smallest relevant test.")
      (let [seen (atom nil)
            model (k/mock-model
                   (fn [request]
                     (reset! seen request)
                     {:type :final :output "ok"}))
            local-tools (tools/local {:cwd (str root)})
            all-tools (conj local-tools (k/responses-web-search))
            agent (k/agent
                   {:name "local-agent"
                    :model {:id "fake" :transport model}
                    :instructions
                    (k/prompt
                     (k/prompt-file (str (.resolve root "AGENTS.md")))
                     "Do not commit changes.")
                    :tools all-tools
                    :input-schema any?
                    :output-schema string?})
            run (k/run! agent "inspect")]
        (is (= :completed (:status run)))
        (is (re-find #"Run the smallest relevant test"
                     (:instructions @seen)))
        (is (re-find #"Do not commit changes"
                     (:instructions @seen)))
        (is (= "Run the smallest relevant test.\n\nDo not commit changes."
               (:instructions @seen)))
        (is (= #{"read" "write" "edit" "bash" "search"}
               (->> (:tools @seen)
                    (filter #(= :function (:kind %)))
                    (map :name)
                    set)))
        (is (= "web_search"
               (get-in (first (filter #(= :hosted (:kind %))
                                      (:tools @seen)))
                       [:spec :type]))))
      (finally
        (delete-tree! root)))))

(deftest cancelling-bash-terminates-its-process
  (when-not (str/includes? (str/lower-case (System/getProperty "os.name"))
                           "win")
    (let [root (temp-directory)
          cancel (atom false)]
      (try
        (let [bash (by-name (tools/local {:cwd (str root)}) "bash")
              calls (atom 0)
              model (k/mock-model
                     (fn [_]
                       (if (= 1 (swap! calls inc))
                         {:type :tool-calls
                          :calls [{:id "bash_1"
                                   :name "bash"
                                   :input {:command
                                           "echo $$ > process.pid; sleep 10"}}]}
                         {:type :final :output "late"})))
              agent (k/agent {:name "process-cancellation"
                              :model {:id "fake" :transport model}
                              :instructions "Run the command."
                              :tools [bash]
                              :output-schema string?})
              result (future (k/run! agent "input" {:cancel cancel}))
              pid-file (.resolve root "process.pid")]
          (try
            (loop [attempt 0]
              (when (and (< attempt 100)
                         (not (Files/exists pid-file
                                            (make-array java.nio.file.LinkOption
                                                        0))))
                (Thread/sleep 10)
                (recur (inc attempt))))
            (is (Files/exists pid-file
                              (make-array java.nio.file.LinkOption 0)))
            (let [pid (Long/parseLong (str/trim (slurp (.toFile pid-file))))]
              (reset! cancel true)
              (is (= :cancelled (:status (deref result 2000 ::timeout))))
              (loop [attempt 0]
                (let [process (.orElse (ProcessHandle/of pid) nil)]
                  (when (and (< attempt 100) process (.isAlive process))
                    (Thread/sleep 10)
                    (recur (inc attempt)))))
              (let [process (.orElse (ProcessHandle/of pid) nil)]
                (is (or (nil? process) (not (.isAlive process))))))
            (finally
              (future-cancel result))))
        (finally
          (delete-tree! root))))))

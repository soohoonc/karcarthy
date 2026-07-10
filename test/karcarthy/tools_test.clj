(ns karcarthy.tools-test
  (:require [clojure.test :refer [deftest is testing]]
            [karcarthy :as k]
            [karcarthy.prompt :as prompt]
            [karcarthy.tools :as tools])
  (:import [java.nio.file Files Path]
           [java.nio.file.attribute FileAttribute]))

(defn- temp-directory []
  (Files/createTempDirectory "karcarthy-coding-"
                             (make-array FileAttribute 0)))

(defn- delete-tree! [^Path root]
  (doseq [file (reverse (file-seq (.toFile root)))]
    (Files/deleteIfExists (.toPath file))))

(defn- by-name [tools name]
  (first (filter #(= name (:name %)) tools)))

(deftest minimal-workspace-tools-work-together
  (let [root (temp-directory)]
    (try
      (let [tools (tools/workspace {:cwd (str root)})
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

(deftest prompt-and-tools-stay-in-sync
  (let [root (temp-directory)]
    (try
      (spit (.toFile (.resolve root "AGENTS.md"))
            "Run the smallest relevant test.")
      (let [seen (atom nil)
            model (k/fake-model
                   (fn [request]
                     (reset! seen request)
                     {:type :final :output "ok"}))
            local-tools (tools/workspace {:cwd (str root)})
            all-tools (conj local-tools (k/openai-web-search))
            agent (k/agent
                   {:name "workspace-agent"
                    :model {:provider :openai :id "fake" :transport model}
                    :instructions
                    (prompt/workspace
                     {:cwd (str root)
                      :tools all-tools
                      :append "Do not commit changes."})
                    :tools all-tools
                    :input any?
                    :output string?})
            run (k/run! agent "inspect")]
        (is (= :completed (:status run)))
        (is (re-find #"Available tools" (get-in agent [:config :instructions])))
        (is (re-find #"Run the smallest relevant test"
                     (get-in agent [:config :instructions])))
        (is (re-find #"Do not commit changes"
                     (get-in agent [:config :instructions])))
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

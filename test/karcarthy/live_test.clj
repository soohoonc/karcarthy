(ns karcarthy.live-test
  "Paid end-to-end verification of the Responses transport and Agent bridge."
  (:require [clojure.string :as str]
            [clojure.test :as t :refer [deftest is]]
            [karcarthy :as k])
  (:import [java.nio.charset StandardCharsets]
           [java.nio.file Files Path]
           [java.nio.file.attribute FileAttribute]))

(defn- configured-model []
  (or (System/getenv "KARCARTHY_OPENAI_MODEL") "gpt-5.6"))

(defn- live? []
  (= "1" (System/getenv "KARCARTHY_LIVE")))

(defn- credentials? []
  (not (str/blank? (System/getenv "OPENAI_API_KEY"))))

(defn- temp-directory []
  (Files/createTempDirectory "karcarthy-live-coding-"
                             (make-array FileAttribute 0)))

(defn- delete-tree! [^Path root]
  (doseq [file (reverse (file-seq (.toFile root)))]
    (Files/deleteIfExists (.toPath file))))

(deftest responses-expands-and-runs-an-agent
  (is (live?) "Set KARCARTHY_LIVE=1 to authorize the paid live test.")
  (is (credentials?) "Set OPENAI_API_KEY in the process environment.")
  (when (and (live?) (credentials?))
    (let [agent-tool (k/agent)
          architect
          (k/agent
           {:name "live-architect"
            :model {:transport :responses
                    :provider :openai
                    :id (configured-model)
                    :reasoning :low
                    :timeout-ms 180000}
            :instructions
            (str
             "This is an end-to-end harness test. Call the agent tool exactly "
             "once. Its source argument must be exactly: "
             "(agent {:name \"increment\" :input int? :output int?} "
             "[n] (inc n)) "
             "Pass 41 as its input. After the tool returns, answer with only "
             "the decimal result, with no explanation.")
            :tools [agent-tool]
            :output string?
            :max-turns 3})
          run (k/run! architect nil
                      {:limits {:model-calls 3
                                :generated-forms 1
                                :agent-depth 2
                                :parallelism 2
                                :deadline-ms 180000}})
          event-types (set (map :type (:events run)))]
      (is (= :completed (:status run)) (pr-str (:error run)))
      (is (= "42" (some-> (:output run) str/trim)))
      (is (= 1 (get-in run [:usage :generated-forms])))
      (is (contains? event-types :tool/completed))
      (is (contains? event-types :program/evaluated))
      (is (= ["live-architect" "increment"]
             (->> (:events run)
                  (filter #(= :agent/started (:type %)))
                  (map :agent)
                  vec))))))

(deftest responses-agent-inspects-and-edits-local-files
  (is (live?) "Set KARCARTHY_LIVE=1 to authorize the paid live test.")
  (is (credentials?) "Set OPENAI_API_KEY in the process environment.")
  (when (and (live?) (credentials?))
    (let [root (temp-directory)
          file (.resolve root "greeting.txt")]
      (try
        (Files/writeString file "hello PLACEHOLDER\n" StandardCharsets/UTF_8
                           (make-array java.nio.file.OpenOption 0))
        (let [tools (k/local-tools {:cwd (str root)})
              coder
              (k/agent
               {:name "live-local-agent"
                :model {:transport :responses
                        :provider :openai
                        :id (configured-model)
                        :reasoning :low
                        :timeout-ms 180000}
                :instructions
                (k/prompt
                 (k/system-prompt)
                 "For this test, inspect the target before changing it and use the edit tool for the change.")
                :tools tools
                :input any?
                :output string?
                :max-turns 6})
              run
              (k/run! coder
                      (str "In greeting.txt, replace the exact text PLACEHOLDER "
                           "with karcarthy. Read the result, then briefly confirm.")
                      {:limits {:model-calls 6
                                :parallelism 2
                                :deadline-ms 180000}})
              used-tools (->> (:events run)
                              (filter #(= :tool/started (:type %)))
                              (map :tool)
                              vec)]
          (is (= :completed (:status run)) (pr-str (:error run)))
          (is (= "hello karcarthy\n"
                 (Files/readString file StandardCharsets/UTF_8)))
          (is (some #{"read"} used-tools) (pr-str used-tools))
          (is (some #{"edit"} used-tools) (pr-str used-tools)))
        (finally
          (delete-tree! root))))))

(defn -main [& _]
  (when-not (live?)
    (println "Set KARCARTHY_LIVE=1 to authorize this paid test.")
    (System/exit 2))
  (when-not (credentials?)
    (println "Set OPENAI_API_KEY in the process environment; do not put it in source.")
    (System/exit 2))
  (let [{:keys [fail error]} (t/run-tests 'karcarthy.live-test)]
    (shutdown-agents)
    (System/exit (if (pos? (+ (or fail 0) (or error 0))) 1 0))))

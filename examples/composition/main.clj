(ns example.composition
  (:require [karcarthy :as k]))

(defn output! [run]
  (if (= :completed (:status run))
    (:output run)
    (throw (ex-info "Agent Run failed" {:run run}))))

(defn review-system [cwd]
  (let [tools
        (k/local-tools
         {:cwd cwd
          :approval {:read :never
                     :search :never
                     :write :always
                     :edit :always
                     :bash :always}})

        reviewer-options
        {:limits {:model-calls 12
                  :deadline-ms 120000}
         :approval (constantly false)}

        security-reviewer
        (k/agent
         {:name "security-reviewer"
          :model {:transport :responses :id "gpt-5.6"}
          :instructions
          "Inspect the repository for concrete security problems. Cite files."
          :tools tools
          :input string?
          :output string?})

        api-reviewer
        (k/agent
         {:name "api-reviewer"
          :model {:transport :responses :id "gpt-5.6"}
          :instructions
          "Inspect the repository for API compatibility problems. Cite files."
          :tools tools
          :input string?
          :output string?})

        editor
        (k/agent
         {:name "review-editor"
          :model {:transport :responses :id "gpt-5.6"}
          :instructions
          "Combine the supplied reviews. Remove duplicates and unsupported claims."
          :input string?
          :output string?})]

    (fn review-team [request]
      (let [[security api]
            (->> [security-reviewer api-reviewer]
                 (mapv #(future (k/run! % request reviewer-options)))
                 (mapv deref)
                 (mapv output!))
            editor-prompt
            (str "Request:\n" request
                 "\n\nSecurity review:\n" security
                 "\n\nAPI review:\n" api)]
        (output! (k/run! editor editor-prompt
                         {:limits {:model-calls 4
                                   :deadline-ms 60000}}))))))

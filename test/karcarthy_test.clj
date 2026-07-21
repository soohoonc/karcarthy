(ns karcarthy-test
  (:require [clojure.test :refer [deftest is]]
            [karcarthy :as k]))

(deftest facade-exposes-only-the-native-harness
  (doseq [sym '[agent defagent tool deftool agent? tool?
                run! output context
                session session? session-id get-items add-items!
                pop-item! clear-session!
                model! emit! events mock-model
                monitor monitor-state
                hosted-tool hosted-tool?
                local-tools prompt prompt-file
                responses-web-search connect-mcp! mcp-tools close-mcp!
                serve-acp!
                definition expansion
                schema-valid? explain-schema schema->json-schema]]
    (is (some? (ns-resolve 'karcarthy sym)) (str "missing " sym)))
  (doseq [removed '[run pipe branch delegate reduce revise route continue
                    dynamic evolve mock-runner fn-runner process-runner
                    claude-runner codex-runner openai-runner acp-runner
                    invoke! spawn! await! await-all!
                    as-tool source-form expanded-form monitor-view print-monitor
                    fake-model
                    handoff! environment conversation-state? model-transport
                    memory-session atom-session output! run-output
                    workspace-tools workspace-prompt
                    read-agent-form check-agent-form! eval-agent-form!
                    compile-agent! system-prompt
                    contract-valid? explain-contract contract->json-schema]]
    (is (nil? (get (ns-publics 'karcarthy) removed))
        (str "still exports " removed))))

(deftest facade-macros-capture-source
  (let [agent (k/agent {:name "facade"
                        :model {:id "fake"
                                :transport (k/mock-model (constantly "ok"))}
                        :instructions "answer"
                        :output-schema string?})]
    (is (k/agent? agent))
    (is (seq (k/definition agent)))
    (is (= "facade" (:name agent)))
    (is (= "answer" (:instructions agent)))
    (is (nil? (:config agent)))))

(deftest model-id-shorthand-is-lowered-once
  (let [agent (k/agent {:name "short"
                        :model "gpt-5.6"
                        :instructions "answer"})]
    (is (= {:transport :responses
            :provider :openai
            :id "gpt-5.6"}
           (:model agent)))))

(deftest direct-namespaces-are-small-and-discoverable
  (doseq [[namespace symbols]
          {'karcarthy.agent '[agent defagent agent? definition expansion]
           'karcarthy.tool '[tool deftool tool? hosted-tool hosted-tool?]
           'karcarthy.run '[run! output context model! emit! events mock-model]
           'karcarthy.session '[Session session session? session-id get-items
                                add-items! pop-item! clear-session!]
           'karcarthy.schema '[valid? explain json-schema]
           'karcarthy.eval '[read-expression]}]
    (require namespace)
    (doseq [sym symbols]
      (is (some? (ns-resolve namespace sym))
          (str "missing " namespace "/" sym))))
  (is (nil? (ns-resolve 'karcarthy.session 'memory-session))))

(deftest agents-do-not-accept-function-bodies
  (is (thrown? clojure.lang.ArityException
               (macroexpand
                '(karcarthy/agent
                  {:name "function-backed"} [input] input))))
  (is (thrown? clojure.lang.ArityException
               (macroexpand
                '(karcarthy/defagent
                  function-backed {} [input] input)))))

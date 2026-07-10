(ns karcarthy-test
  (:require [clojure.test :refer [deftest is]]
            [karcarthy :as k]))

(deftest facade-exposes-only-the-native-harness
  (doseq [sym '[agent defagent tool deftool agent? tool?
                run! context
                memory-session session? session-id get-items add-items!
                pop-item! clear-session!
                model! emit! events fake-model
                run-monitor monitor-view print-monitor
                hosted-tool hosted-tool?
                local-tools prompt prompt-file system-prompt
                responses-web-search connect-mcp! mcp-tools close-mcp!
                serve-acp!
                read-agent-form check-agent-form! eval-agent-form!
                compile-agent! definition expansion]]
    (is (some? (ns-resolve 'karcarthy sym)) (str "missing " sym)))
  (doseq [removed '[run pipe branch delegate reduce revise route continue
                    dynamic evolve mock-runner fn-runner process-runner
                    claude-runner codex-runner openai-runner acp-runner
                    invoke! spawn! await! await-all!
                    as-tool source-form expanded-form
                    handoff! environment conversation-state? model-transport
                    workspace-tools workspace-prompt]]
    (is (nil? (get (ns-publics 'karcarthy) removed))
        (str "still exports " removed))))

(deftest forwarding-macros-capture-source
  (let [agent (k/agent {:name "facade" :output string?}
                       [x] (str x))]
    (is (k/agent? agent))
    (is (seq (k/definition agent)))))

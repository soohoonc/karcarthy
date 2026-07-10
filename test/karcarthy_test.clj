(ns karcarthy-test
  (:require [clojure.test :refer [deftest is]]
            [karcarthy :as k]))

(deftest facade-exposes-only-the-native-harness
  (doseq [sym '[agent defagent tool deftool agent? tool?
                run! invoke! spawn! await! await-all! handoff! as-tool
                context model! emit! events model-transport fake-model
                hosted-tool hosted-tool?
                workspace-tools workspace-prompt
                responses-web-search connect-mcp! mcp-tools close-mcp!
                serve-acp!
                read-agent-form check-agent-form! eval-agent-form!
                compile-agent! source-form expanded-form]]
    (is (some? (ns-resolve 'karcarthy sym)) (str "missing " sym)))
  (doseq [removed '[run pipe branch delegate reduce revise route continue
                    dynamic evolve mock-runner fn-runner process-runner
                    claude-runner codex-runner openai-runner acp-runner]]
    (is (nil? (get (ns-publics 'karcarthy) removed))
        (str "still exports " removed))))

(deftest forwarding-macros-capture-source
  (let [agent (k/agent {:name "facade" :output string?}
                       [_ x] (str x))]
    (is (k/agent? agent))
    (is (seq (k/source-form agent)))))

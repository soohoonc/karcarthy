(ns karcarthy.runner.codex-test
  (:require [clojure.test :refer [deftest is testing]]
            [karcarthy.core :as k]
            [karcarthy.runner.codex :as codex]))

(deftest subagent-config
  (testing "subagents lower to Codex custom agent config keys"
    (let [reviewer (k/subagent "security-reviewer"
                               "Use for security review."
                               "Find concrete security risks."
                               :model "gpt-5.4-mini"
                               :reasoning-effort :medium
                               :sandbox-mode :read-only
                               :mcp-servers {:github {:command "github-mcp"}}
                               :nicknames ["Sec"])
          config   (codex/subagent->config reviewer)]
      (is (= "security-reviewer" (:name config)))
      (is (= "Use for security review." (:description config)))
      (is (= "Find concrete security risks." (:developer_instructions config)))
      (is (= "gpt-5.4-mini" (:model config)))
      (is (= "medium" (:model_reasoning_effort config)))
      (is (= "read-only" (:sandbox_mode config)))
      (is (= {:github {:command "github-mcp"}} (:mcp_servers config)))
      (is (= ["Sec"] (:nickname_candidates config))))))

(deftest agents-config
  (testing "global agent settings use Codex config keys"
    (is (= {:max_threads 4
            :max_depth 1
            :job_max_runtime_seconds 900}
           (codex/agents->config :max-threads 4
                                 :max-depth 1
                                 :job-max-runtime-seconds 900)))))

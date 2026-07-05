(ns karcarthy.runner.codex-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [karcarthy.core :as k]
            [karcarthy.runner.codex :as codex]))

(deftest codex-rejects-unenforced-tool-allowlists
  (let [runner (codex/codex-runner)
        agent (k/agent {:name "a" :instructions "i" :tools ["WebSearch"]})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"does not support agent :tools"
                          (k/run-agent runner agent "x")))))

(defn- after
  "The argv element immediately following `flag`, or nil if `flag` is absent."
  [argv flag]
  (second (drop-while #(not= % flag) argv)))

(deftest command-building
  (testing "agent fields and runner options map to codex exec flags"
    (let [agent (k/agent {:name "researcher"
                          :instructions "Research carefully."
                          :model "gpt-5"})
          argv  (codex/command agent {:codex-bin "/opt/bin/codex"
                                      :dir "/tmp/karc"
                                      :sandbox :workspace-write
                                      :color :never
                                      :extra-args ["--json"]})]
      (is (= ["/opt/bin/codex" "exec"] (subvec argv 0 2)))
      (is (some #{"--ephemeral"} argv))
      (is (some #{"--skip-git-repo-check"} argv))
      (is (= "workspace-write" (after argv "--sandbox")))
      (is (= "never" (after argv "--color")))
      (is (= "/tmp/karc" (after argv "--cd")))
      (is (= "gpt-5" (after argv "--model")))
      (is (some #{"--json"} argv))
      (is (= "-" (last argv))))))

(deftest prompt-carries-instructions-and-input
  (testing "the stdin prompt folds in the agent spec and the flowing input"
    (let [p (codex/prompt (k/agent {:name "researcher"
                                    :instructions "Research carefully."})
                          "find X")]
      (is (re-find #"Agent name: researcher" p))
      (is (re-find #"Research carefully" p))
      (is (re-find #"Input:\nfind X" p)))))

(deftest command-building-model-override
  (testing "runner :model overrides the agent model"
    (let [argv (codex/command (k/agent {:name "a"
                                        :instructions "i"
                                        :model "gpt-5"})
                              {:model "gpt-5.1"})]
      (is (= "gpt-5.1" (after argv "--model"))))))

(deftest codex-runner-runs-a-command
  (testing "the runner writes the full prompt (with input) to stdin"
    (let [script (java.io.File/createTempFile "karcarthy_codex_runner" ".sh")
          _      (spit script "#!/bin/sh\nprintf 'seen: '\ncat\n")
          _      (.setExecutable script true)
          runner (codex/codex-runner {:codex-bin (.getPath script)
                                      :timeout-ms 3000})
          result (k/run-agent runner
                              (k/agent {:name "codex"
                                        :instructions "echo input"})
                              "hello")]
      (is (k/ok? result))
      (is (str/starts-with? (:text result) "seen: "))
      (is (str/includes? (:text result) "Agent name: codex"))
      (is (str/includes? (:text result) "Input:\nhello"))
      (is (= :codex (get-in result [:raw :runner])))
      (is (= "exec" (second (get-in result [:raw :argv]))))
      (is (= "-" (last (get-in result [:raw :argv])))))))

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

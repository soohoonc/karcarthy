# karcarthy - guide for Codex

Homoiconic agent **orchestration** in Clojure. Agents, tools, and workflows are
plain EDN data; karcarthy delegates the inner agent loop (model calls + tool
execution) to an external **runner** instead of reimplementing it.

## Commands

```bash
clojure -M:test                                    # offline test suite (no network/API)
clojure -M -m karcarthy.demo                       # offline demo
clojure -M -e '(load-file "examples/clojure/live.clj")'       # live demo (paid Claude CLI)
```

## Layout

| File | Role |
|------|------|
| `src/karcarthy.clj` | facade namespace re-exporting the public API under one alias (`(require '[karcarthy :as k])`) |
| `src/karcarthy/core.clj` | data model (`agent`, `subagent`), spec validation, `result`, the `Runner` protocol, `mock-runner`, `fn-runner`, and the `defagent` / `defsubagent` macros |
| `src/karcarthy/orchestrate.clj` | the workflow DSL - `pipe` / `step` / `branch` / `delegate` / `reduce` / `revise` / `route` / `continue`, the `run` interpreter (a `run-node` multimethod), and `defworkflow` / `workflow?` |
| `src/karcarthy/dynamic.clj` | dynamic workflows: the `dynamic` op-loop node, `agent-ref` / `workflow-ref`, mutable run state, and op application (`step!`) |
| `src/karcarthy/schema.clj` | EDN and JSON schema reference values for public workflow data |
| `src/karcarthy/rewrite.clj` | structural workflow rewrites: `agents`, `over`, and `configure` |
| `src/karcarthy/self.clj` | safe EDN parsing for agent-authored workflows and agents; `evolve` extension node |
| `src/karcarthy/edn.clj` | internal helper extracting the first EDN map from model output (`clojure.edn`, never `eval`) |
| `src/karcarthy/observe.clj` | internal observation-event helpers shared by `core` and `orchestrate` (`:observe` callback) |
| `src/karcarthy/proc.clj` | subprocess execution with timeout, shared by the subprocess-backed runners |
| `src/karcarthy/cli.clj` | CLI entry point and language-agnostic JSON bridge behind `bin/karcarthy` |
| `src/karcarthy/demo.clj` | offline demo (`clojure -M -m karcarthy.demo`) |
| `src/karcarthy/runner/claude.clj` | Claude runner, backed by `claude -p` |
| `src/karcarthy/runner/codex.clj` | Codex runner, backed by `codex exec`, plus Codex custom-agent config lowering |
| `src/karcarthy/runner/acp.clj` | ACP (Agent Client Protocol) runner over stdio JSON-RPC |
| `src/karcarthy/runner/process.clj` | process and shell runners; wrap CLIs or shell commands as agents (prompt → stdin, stdout → result) |
| `src/karcarthy/runner/openai.clj` | OpenAI runner via `resources/karcarthy/openai_runner.py` |
| `test/…` | mirrors `src/`; the test runner lists namespaces in `test/karcarthy/test_runner.clj` |

## Conventions

- **Dependencies: Maven Central only.** Clojars is blocked in the dev sandbox.
  HTTP uses Java's built-in client or shelling out - no HTTP-client dep.
- **Everything is data.** Each entity is a map tagged with `:karcarthy/type`
  (`:agent`, `:result`, `:pipe`, `:branch`, …). Prefer plain maps over records.
- **A runner** implements `karcarthy.core/Runner` (`-run`) and returns a
  result map: `{:karcarthy/type :result :ok? … :text … :agent … :raw …}`.
- **Adding a workflow node:** add a constructor in `orchestrate.clj`, a `run-node`
  defmethod, schema entries in `schema.clj`, and tests.
- **Model-facing EDN protocols self-repair.** Nodes that parse model replies
  (planner, evaluator, router, dynamic ops) re-ask the model with the error and
  its previous reply before failing (`:edn-retries` run option, default 1).
  New protocol-reading nodes should go through `elicit!` in `orchestrate.clj`.
- **Adding a test namespace:** register it in `test/karcarthy/test_runner.clj`
  (zero-dependency test runner; no Clojars test libs).
- **Prefer pure, offline-testable builders** (e.g. `karcarthy.runner.claude/command`,
  `karcarthy.runner.openai/request`); gate any live/paid calls behind env vars
  (`KARCARTHY_LIVE`) so `clojure -M:test` stays offline and free.
- **Driving real Claude sub-agents:** use `:system-prompt-mode :replace` and
  disable tools (`:extra-args ["--disallowedTools" "…"]`) so they answer
  directly instead of inheriting Claude's interactive persona.

Contribution workflow and PR expectations: [CONTRIBUTING.md](CONTRIBUTING.md).

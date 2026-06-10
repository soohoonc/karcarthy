# karcarthy - guide for Codex

Homoiconic agent **orchestration** in Clojure. Agents, tools, and workflows are
plain EDN data; the inner agent loop (model calls + tool execution) is delegated
to an external **runner** rather than reimplemented here.

## Commands

```bash
clojure -M:test                                    # offline test suite (no network/API)
clojure -M -m karcarthy.demo                       # offline demo
clojure -M -e '(load-file "examples/clojure/live.clj")'       # live demo (paid Claude CLI)
```

## Layout

| File | Role |
|------|------|
| `src/karcarthy/core.clj` | data model (`agent`), spec validation, `result`, the `Runner` protocol, `mock-runner`, `fn-runner`, and the `defagent` macro |
| `src/karcarthy/orchestrate.clj` | the workflow DSL - `pipe` / `map` / `reduce` / `iterate` / `bind`, the `run` interpreter (a `run-node` multimethod), and `defworkflow` / `workflow?` |
| `src/karcarthy/schema.clj` | EDN and JSON schema reference values for public workflow data |
| `src/karcarthy/rewrite.clj` | structural workflow rewrites: `agents`, `over`, and `config` |
| `src/karcarthy/self.clj` | safe EDN parsing for agent-authored workflows and agents; `evolve` extension node |
| `src/karcarthy/runner/claude.clj` | Claude runner, backed by `claude -p` |
| `src/karcarthy/runner/codex.clj` | Codex runner, backed by `codex exec`, plus Codex custom-agent config lowering |
| `src/karcarthy/runner/process.clj` | process and shell runners; wrap CLIs or shell commands as agents (prompt → stdin, stdout → result) |
| `src/karcarthy/runner/openai.clj` | OpenAI runner via `resources/karcarthy/openai_runner.py` |
| `test/…` | mirrors `src/`; the test runner lists namespaces in `test/karcarthy/test_runner.clj` |

## Conventions

- **Dependencies: Maven Central only.** Clojars is blocked in the dev sandbox.
  HTTP uses Java's built-in client or shelling out - no HTTP-client dep.
- **Everything is data.** Each entity is a map tagged with `:karcarthy/type`
  (`:agent`, `:result`, `:pipe`, `:map`, …). Prefer plain maps over records.
- **A runner** implements `karcarthy.core/Runner` (`-run`) and returns a
  result map: `{:karcarthy/type :result :ok? … :text … :agent … :raw …}`.
- **Adding a workflow node:** add a constructor in `orchestrate.clj`, a `run-node`
  defmethod, schema entries in `schema.clj`, and tests.
- **Adding a test namespace:** register it in `test/karcarthy/test_runner.clj`
  (zero-dependency test runner; no Clojars test libs).
- **Prefer pure, offline-testable builders** (e.g. `claude-command`,
  `openai-request`); gate any live/paid calls behind env vars
  (`KARCARTHY_LIVE`) so `clojure -M:test` stays offline and free.
- **Driving real Claude sub-agents:** use `:system-prompt-mode :replace` and
  disable tools (`:extra-args ["--disallowedTools" "…"]`) so they answer
  directly instead of inheriting Claude's interactive persona.

# karcarthy - guide for Claude Code

Homoiconic agent **orchestration** in Clojure. Agents, tools, and workflows are
plain EDN data; the inner agent loop (model calls + tool execution) is delegated
to an external **runner** rather than reimplemented here.

## Commands

```bash
clojure -M:test                                    # offline test suite (no network/API)
KARCARTHY_LIVE=1 clojure -M:test                   # also runs the live `claude -p` test
clojure -M -m karcarthy.demo                       # offline demo
clojure -M -e '(load-file "examples/live_orchestrate.clj")'   # live demo (paid claude -p)
```

## Layout

| File | Role |
|------|------|
| `src/karcarthy/core.clj` | data model (`agent`), spec validation, `result`, the `Runner` protocol, the offline `mock-runner`, and the `defagent` macro |
| `src/karcarthy/orchestrate.clj` | the workflow DSL - `chain` / `parallel` / `route` / `refine` / `orchestrate` / `handoff`, the `run` interpreter (a `run-node` multimethod), and `defworkflow` / `workflow?` |
| `src/karcarthy/session.clj` | `converse` - multi-turn conversations that thread the runner session (memory) |
| `src/karcarthy/runner/claude.clj` | preferred Claude runner namespace; delegates to the compatibility implementation in `harness/claude.clj` |
| `src/karcarthy/runner/command.clj` | preferred command runner namespace; wrap any CLI as an agent (prompt → stdin, stdout → result) |
| `src/karcarthy/runner/openai.clj` | preferred OpenAI runner namespace; OpenAI Agents SDK via `resources/karcarthy/openai_runner.py` |
| `test/…` | mirrors `src/`; the runner lists namespaces in `test/karcarthy/test_runner.clj` |

## Conventions

- **Dependencies: Maven Central only.** Clojars is blocked in the dev sandbox.
  HTTP uses Java's built-in client or shelling out - no HTTP-client dep.
- **Everything is data.** Each entity is a map tagged with `:karcarthy/type`
  (`:agent`, `:result`, `:chain`, `:route`, …). Prefer plain maps over records.
- **A runner** implements `karcarthy.core/Runner` (`-run`) and returns a
  result map: `{:karcarthy/type :result :ok? … :text … :agent … :raw …}`.
- **Adding a workflow node:** add a constructor in `orchestrate.clj`, a `run-node`
  defmethod, and tests.
- **Adding a test namespace:** register it in `test/karcarthy/test_runner.clj`
  (zero-dependency runner; no Clojars test libs).
- **Prefer pure, offline-testable builders** (e.g. `claude-command`,
  `openai-request`); gate any live/paid calls behind env vars
  (`KARCARTHY_LIVE`) so `clojure -M:test` stays offline and free.
- **Driving real Claude sub-agents:** use `:system-prompt-mode :replace` and
  disable tools (`:extra-args ["--disallowedTools" "…"]`) so they answer
  directly instead of inheriting Claude Code's interactive persona.

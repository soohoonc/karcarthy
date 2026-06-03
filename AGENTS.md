# karcarthy - guide for Codex

Homoiconic agent **orchestration** in Clojure. Agents, tools, and workflows are
plain EDN data; the inner agent loop (model calls + tool execution) is delegated
to an external **adapter** rather than reimplemented here.

## Commands

```bash
clojure -M:test                                    # offline test suite (no network/API)
clojure -M -m karcarthy.demo                       # offline demo
clojure -M -e '(load-file "examples/clojure/live.clj")'       # live demo (paid Claude CLI)
```

## Layout

| File | Role |
|------|------|
| `src/karcarthy/core.clj` | data model (`agent`), spec validation, `result`, the `Adapter` protocol, the offline `mock-adapter`, and the `defagent` macro |
| `src/karcarthy/orchestrate.clj` | the workflow DSL - `pipe` / `map` / `reduce` / `iterate` / `bind`, the `run` interpreter (a `run-node` multimethod), and `defworkflow` / `workflow?` |
| `src/karcarthy/schema.clj` | EDN and JSON schema reference values for public workflow data |
| `src/karcarthy/rewrite.clj` | structural workflow rewrites: `agents`, `over`, and `config` |
| `src/karcarthy/self.clj` | safe EDN parsing for agent-authored workflows and agents; `evolve` extension node |
| `src/karcarthy/adapter/claude.clj` | Claude CLI adapter |
| `src/karcarthy/adapter/command.clj` | command adapter; wrap any CLI as an agent (prompt → stdin, stdout → result) |
| `src/karcarthy/adapter/openai.clj` | OpenAI Agents SDK adapter via `resources/karcarthy/openai_runner.py` |
| `test/…` | mirrors `src/`; the test runner lists namespaces in `test/karcarthy/test_runner.clj` |

## Conventions

- **Dependencies: Maven Central only.** Clojars is blocked in the dev sandbox.
  HTTP uses Java's built-in client or shelling out - no HTTP-client dep.
- **Everything is data.** Each entity is a map tagged with `:karcarthy/type`
  (`:agent`, `:result`, `:pipe`, `:map`, …). Prefer plain maps over records.
- **An adapter** implements `karcarthy.core/Adapter` (`-run`) and returns a
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

# Changelog

All notable changes are documented here, following
[Keep a Changelog](https://keepachangelog.com/) and
[Semantic Versioning](https://semver.org/).

## [Unreleased] — 0.0.2

Early and not yet released. What works so far:

### Added
- EDN data model for agents, with `clojure.spec` validation and the `defagent` /
  `defworkflow` macros.
- The `Adapter` protocol and four adapters: `mock-adapter` (offline), `claude-cli`
  (`claude -p`, buffered or `stream-json` streaming, sessions via `:resume`),
  `command` (wrap any CLI as an agent), and `openai` (OpenAI Agents SDK via a
  bundled Python bridge).
- Workflow nodes interpreted by `run`: `pipe`, `branch`, `delegate`, `reduce`,
  `revise`, `route`, `continue`, and `dynamic`.
- Dynamic workflows: an agent can emit EDN ops (`:define`, `:patch`, `:remove`,
  `:call`, `:spawn`, `:complete`) during a run; `agent-ref` and `workflow-ref`
  resolve against that run's state.
- `karcarthy.self`: safe EDN parsing for agent-authored workflows and agents,
  plus the `evolve` extension node for runtime instruction/model/tool patches.
- `karcarthy.schema`: EDN and JSON schema reference values.
- `karcarthy.proc`: subprocess execution with `:timeout-ms` on every shell
  adapter; fault-isolated workflow nodes; bounded concurrency; tolerant routing.
- A `karcarthy` facade namespace re-exporting the common API under one alias.
- `karcarthy.cli`: a JSON bridge (workflow in, result out) so any language can
  drive karcarthy. Python and TypeScript examples included, plus a
  `COMPARISON.md` vs PydanticAI / Agno / Vercel AI SDK.
- Adapters selectable by id: pass a registry map `{id -> Adapter}` and set
  `:adapter` on an agent; workflows stay plain data.
- Usage examples in Java, Kotlin, and Scala; MIT license; CI.
- JavaScript and Clojure examples for Claude dynamic-agent style workflows and
  OpenAI Deep Research request setup.

[Unreleased]: https://github.com/soohoonc/karcarthy

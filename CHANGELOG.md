# Changelog

All notable changes are documented here, following
[Keep a Changelog](https://keepachangelog.com/) and
[Semantic Versioning](https://semver.org/).

## [Unreleased] — 0.0.2

Early and not yet released. What works so far:

### Added
- EDN data model for agents, with `clojure.spec` validation and the `defagent` /
  `defworkflow` macros.
- The `Runner` protocol and four adapters: `mock` (offline), `claude-cli`
  (`claude -p`, buffered or `stream-json` streaming, sessions via `:resume`),
  `command` (wrap any CLI as an agent), and `openai` (OpenAI Agents SDK via a
  bundled Python runner).
- Workflow nodes interpreted by `run`: `chain`, `parallel` / `parallel*`,
  `route`, `refine`, `orchestrate`, `handoff`; multi-turn `session/converse`.
- `karcarthy.self`: agents author workflows (`run-authored`) and edit their own
  behavior (`evolve`) at runtime; a runtime-editable agent `registry`; safe EDN
  parsing (data only, via `clojure.edn`).
- `karcarthy.dynamic`: a data-only meta-execution loop where a controller agent
  emits EDN operations to mutate a living runtime (`:put`, `:patch`, `:remove`,
  `:call`, `:emit`, `:return`, `:complete`) while keeping early dynamic op names
  as compatibility aliases.
- `karcarthy.proc`: subprocess execution with `:timeout-ms` on every shell
  runner; fault-isolated workflow nodes; bounded concurrency; tolerant routing.
- A `karcarthy` facade namespace re-exporting the common API under one alias.
- `karcarthy.cli`: a JSON bridge (workflow in, result out) so any language can
  drive karcarthy. Python and TypeScript examples included, plus a
  `COMPARISON.md` vs PydanticAI / Agno / Vercel AI SDK.
- Runners selectable by id: pass a registry map `{id -> Runner}` and set
  `:runner` on an agent; workflows stay plain data. The old `Harness` /
  `:harness` names, plus old `flow` API names, remain as compatibility aliases.
- OpenTelemetry instrumentation via `karcarthy.otel/instrument` for workflow
  nodes, embedded functions, and agent calls.
- Usage examples in Java, Kotlin, and Scala; MIT license; CI.

[Unreleased]: https://github.com/soohoonc/karcarthy

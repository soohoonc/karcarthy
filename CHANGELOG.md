# Changelog

All notable changes are documented here, following
[Keep a Changelog](https://keepachangelog.com/) and
[Semantic Versioning](https://semver.org/).

## [Unreleased] — 0.0.2

Early and not yet released. What works so far:

### Added
- EDN data model for agents, with `clojure.spec` validation and the `defagent` /
  `defflow` macros.
- The `Harness` protocol and four adapters: `mock` (offline), `claude-cli`
  (`claude -p`, buffered or `stream-json` streaming, sessions via `:resume`),
  `command` (wrap any CLI as an agent), and `openai` (OpenAI Agents SDK via a
  bundled Python runner).
- Flow nodes interpreted by `run-flow`: `chain`, `parallel` / `parallel*`,
  `route`, `refine`, `orchestrate`, `handoff`; multi-turn `session/converse`.
- `karcarthy.self`: agents author flows (`run-authored`) and edit their own
  behavior (`evolve`) at runtime; a runtime-editable agent `registry`; safe EDN
  parsing (data only, via `clojure.edn`).
- `karcarthy.proc`: subprocess execution with `:timeout-ms` on every shell
  harness; fault-isolated flow nodes; bounded concurrency; tolerant routing.
- A `karcarthy` facade namespace re-exporting the common API under one alias.
- Harnesses selectable by id: pass a registry map `{id -> Harness}` and set
  `:harness` on an agent; flows stay plain data.
- Usage examples in Java, Kotlin, and Scala; MIT license; CI.

[Unreleased]: https://github.com/soohoonc/karcarthy

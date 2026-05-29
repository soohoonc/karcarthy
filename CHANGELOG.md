# Changelog

All notable changes to this project are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/), and the project aims to follow
[Semantic Versioning](https://semver.org/).

## [Unreleased]

## [0.2.0] - 2026-05-29

### Added
- `karcarthy.self` - agents author and edit karcarthy at runtime: `run-authored`
  (an agent writes a flow that then runs), `evolve` (an agent edits its own
  definition), a runtime-editable agent `registry` + `agent-ref`, and safe EDN
  parsing (`read-flow` / `read-agent`, data-only via `clojure.edn` - never eval).
  `dsl-reference` teaches the DSL to a model.
- `karcarthy.proc` - subprocess execution with `:timeout-ms`; all shell harnesses
  (claude-cli, command, openai) now accept it and force-kill hung processes.

### Changed
- Composite flow nodes are fault-isolated (a child that throws becomes a not-ok
  result); `parallel`/`orchestrate` use a fixed-thread-pool bounded concurrency;
  `route` matches tolerantly (exact, then case-insensitive, then substring).
- `karcarthy.orchestrate/run-node` is now public - the interpreter's extension
  point for new node types.

## [0.1.0] - 2026-05-29

### Added
- EDN data model for agents with `clojure.spec` validation, plus the `defagent`
  macro (the var name is the agent name; instructions become the docstring).
- The `Harness` protocol and four adapters:
  - `mock` - offline and deterministic, for tests;
  - `claude-cli` - drives `claude -p` (buffered JSON and `stream-json`
    streaming with an `:on-event` callback; sessions via `:resume`);
  - `command` - wrap any CLI/local model as an agent (stdin → stdout);
  - `openai` - the OpenAI Agents SDK via a bundled Python runner.
- Orchestration as data - `chain`, `parallel` / `parallel*`, `route`, `refine`
  (evaluator-optimizer), `orchestrate` (orchestrator-workers), and `handoff` -
  interpreted by `run-flow`; plus `defflow` / `flow?`.
- Multi-turn sessions via `karcarthy.session/converse`.
- An offline demo (`karcarthy.demo`) and a live example
  (`examples/live_orchestrate.clj`).
- MIT license, GitHub Actions CI, and a `tools.build` build script.

[Unreleased]: https://github.com/soohoonc/karcarthy/compare/v0.2.0...HEAD
[0.2.0]: https://github.com/soohoonc/karcarthy/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/soohoonc/karcarthy/releases/tag/v0.1.0

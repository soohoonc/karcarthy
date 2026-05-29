# Changelog

All notable changes to this project are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/), and the project aims to follow
[Semantic Versioning](https://semver.org/).

## [Unreleased]

## [0.1.0] - 2026-05-29

### Added
- EDN data model for agents with `clojure.spec` validation, plus the `defagent`
  macro (the var name is the agent name; instructions become the docstring).
- The `Harness` protocol and four adapters:
  - `mock` — offline and deterministic, for tests;
  - `claude-cli` — drives `claude -p` (buffered JSON and `stream-json`
    streaming with an `:on-event` callback; sessions via `:resume`);
  - `command` — wrap any CLI/local model as an agent (stdin → stdout);
  - `openai` — the OpenAI Agents SDK via a bundled Python runner.
- Orchestration as data — `chain`, `parallel` / `parallel*`, `route`, `refine`
  (evaluator-optimizer), `orchestrate` (orchestrator-workers), and `handoff` —
  interpreted by `run-flow`; plus `defflow` / `flow?`.
- Multi-turn sessions via `karcarthy.session/converse`.
- An offline demo (`karcarthy.demo`) and a live example
  (`examples/live_orchestrate.clj`).
- MIT license, GitHub Actions CI, and a `tools.build` build script.

[Unreleased]: https://github.com/soohoonc/karcarthy/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/soohoonc/karcarthy/releases/tag/v0.1.0

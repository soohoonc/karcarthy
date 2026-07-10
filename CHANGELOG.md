# Changelog

All notable changes are documented here, following
[Keep a Changelog](https://keepachangelog.com/) and
[Semantic Versioning](https://semver.org/).

## Unreleased

### Added

- A native Clojure Agent/Tool harness with contracts, context, memory,
  approvals, limits, cancellation, structured child execution, and events.
- Homoiconic `agent`: `(agent config ...)` constructs an Agent while `(agent)`
  gives a model the same form as a recursive tool capability.
- Model-authored Clojure reading, full executable-position macroexpansion,
  evaluation, Agent verification, recursive invocation, and trace events.
- A direct OpenAI Responses API transport and deterministic in-process fake
  model transport.
- Minimal workspace `read`, `write`, `edit`, `bash`, and ripgrep-backed
  `search` Tools plus separate capability-derived instructions.
- OpenAI-hosted web search as a provider-native Tool.
- An MCP 2025-11-25 stdio client that discovers and adapts remote tools.
- An ACP v1 stdio server with sessions, permissions, cancellation, tool-call
  updates, and session-provided stdio MCP servers.
- An explicit paid live test that asks GPT-5.6 to author and run a child Agent.
- A paid Agent test that inspects and edits a temporary workspace.

### Changed

- The harness now owns the model/tool loop; provider transports only translate
  model I/O.
- Orchestration is normal Clojure control flow and Agent invocation rather than
  a separate workflow language.

### Removed

- The Runner abstraction and subprocess-backed runner adapters.
- The EDN/JSON workflow interpreter, workflow nodes and schemas, dynamic-op
  subsystem, and JSON CLI bridge.
- Legacy cross-language workflow examples. Future non-Clojure integrations use
  the ACP server boundary.

[Unreleased]: https://github.com/soohoonc/karcarthy

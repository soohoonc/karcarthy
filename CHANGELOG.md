# Changelog

All notable changes are documented here, following
[Keep a Changelog](https://keepachangelog.com/) and
[Semantic Versioning](https://semver.org/).

## Unreleased

### Added

- A native Clojure Agent/Tool harness with dynamic instructions, local context,
  optional Sessions, approvals, limits, cancellation, and events.
- Homoiconic `agent`: `(agent config ...)` constructs an Agent while `(agent)`
  gives a model the same form as a recursive tool capability.
- Model-authored Clojure reading, full executable-position macroexpansion,
  evaluation, Agent verification, recursive execution, and program events.
- A Responses-compatible HTTP/SSE transport with configurable endpoint,
  authentication environment, headers, model IDs, normalized streaming
  deltas, and a deterministic in-process fake model transport.
- Minimal local `read`, `write`, `edit`, `bash`, and ripgrep-backed `search`
  Tools plus generic `prompt`, `prompt-file`, and `system-prompt` composition.
- Responses-hosted web search as an explicit endpoint capability.
- An MCP 2025-11-25 stdio client that discovers and adapts remote tools.
- An ACP v1 stdio server with sessions, permissions, cancellation, tool-call
  updates, streaming Agent-message chunks, per-session conversation history,
  session-provided stdio MCP servers, selectable model configuration, and
  aggregate prompt usage compatible with Harbor's ACP-to-ATIF conversion.
- An explicit paid live test that asks GPT-5.6 to author and run a child Agent.
- A paid Agent test that inspects and edits a temporary directory.
- A minimal REPL chat example built from an Agent, `run!`, and a Session.

### Changed

- Documentation now introduces the harness from ordinary Agent use outward,
  separates prompt input from local context and Tool schemas, and presents the
  homoiconic motivation after the programming model.
- The harness now owns the model/tool loop; provider transports only translate
  model I/O.
- Orchestration is normal Clojure control flow plus `run!`, rather than a
  separate workflow or child-call API.
- Model-visible `:instructions` is distinct from local `:context`; local
  context is never exposed automatically.
- Conversation history follows the established Session abstraction. Runs are
  stateless unless supplied a Session; `memory-session` is the process-local
  implementation and applications may provide durable implementations.
- Loop controls are top-level Agent options: `:max-turns` and `:stop-when`.

### Removed

- The Runner abstraction and subprocess-backed runner adapters.
- The EDN/JSON workflow interpreter, workflow nodes and schemas, dynamic-op
  subsystem, and JSON CLI bridge.
- Legacy cross-language workflow examples. Future non-Clojure integrations use
  the ACP server boundary.
- The unrestricted `prepare-step` request mutation hook and the
  directory-specific `workspace-prompt` API.
- Public `invoke!`, `spawn!`, `await!`, and `await-all!`; Agent and Tool bodies
  now receive only their input and use ordinary Clojure plus `run!`.
- The placeholder `handoff!`, Agent `:hooks`, Run `:trace-id`, public
  `model-transport`, and generic conversation-state snapshots.

[Unreleased]: https://github.com/soohoonc/karcarthy

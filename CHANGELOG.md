# Changelog

All notable changes are documented here, following
[Keep a Changelog](https://keepachangelog.com/) and
[Semantic Versioning](https://semver.org/).

## Unreleased

### Added

- A native Clojure Agent/Tool harness with provider-neutral context assembly,
  local environments, explicit conversation state, approvals, limits,
  cancellation, structured child execution, and events.
- Homoiconic `agent`: `(agent config ...)` constructs an Agent while `(agent)`
  gives a model the same form as a recursive tool capability.
- Model-authored Clojure reading, full executable-position macroexpansion,
  evaluation, Agent verification, recursive invocation, and trace events.
- A Responses-compatible HTTP/SSE transport with configurable endpoint,
  authentication environment, headers, model IDs, normalized streaming
  deltas, and a deterministic in-process fake model transport.
- Minimal local `read`, `write`, `edit`, `bash`, and ripgrep-backed `search`
  Tools plus generic `prompt`, `prompt-file`, and `system-prompt` composition.
- Responses-hosted web search as an explicit endpoint capability.
- An MCP 2025-11-25 stdio client that discovers and adapts remote tools.
- An ACP v1 stdio server with sessions, permissions, cancellation, tool-call
  updates, streaming Agent-message chunks, explicit per-session conversation
  state, and session-provided stdio MCP servers.
- An explicit paid live test that asks GPT-5.6 to author and run a child Agent.
- A paid Agent test that inspects and edits a temporary directory.

### Changed

- The harness now owns the model/tool loop; provider transports only translate
  model I/O.
- Orchestration is normal Clojure control flow and Agent invocation rather than
  a separate workflow language.
- Model-visible `:context` is distinct from local `:environment`; the former
  normalizes to `{:system ... :messages [...]}` and the latter is never exposed
  automatically.
- Root conversation state enters and leaves `run!` as an explicit versioned
  value instead of an Agent-name-keyed memory atom.

### Removed

- The Runner abstraction and subprocess-backed runner adapters.
- The EDN/JSON workflow interpreter, workflow nodes and schemas, dynamic-op
  subsystem, and JSON CLI bridge.
- Legacy cross-language workflow examples. Future non-Clojure integrations use
  the ACP server boundary.
- The unrestricted `prepare-step` request mutation hook and the
  directory-specific `workspace-prompt` API.

[Unreleased]: https://github.com/soohoonc/karcarthy

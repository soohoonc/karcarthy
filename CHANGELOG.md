# Changelog

All notable changes are documented here, following
[Keep a Changelog](https://keepachangelog.com/) and
[Semantic Versioning](https://semver.org/).

## Unreleased

### Added

- A native Clojure Agent/Tool harness with dynamic instructions, local context,
  optional Sessions, approvals, limits, cancellation, and events.
- Homoiconic `agent` forms: every model Agent can write and run the same Agent
  form a developer would write.
- A complete, dynamically generated `agent` Tool manual covering the Clojure
  grammar, use and non-use cases, information boundary, execution behavior,
  and the model, Tool, and Agent symbols actually available.
- First-class `:agents`, which makes known specialists available to a parent
  model without a public Agent-to-Tool adapter.
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
- An explicit paid live test that asks GPT-5.6 to author and run a new Agent.
- A paid Agent test that inspects and edits a temporary directory.
- A minimal REPL chat example built from an Agent, `run!`, and a Session.
- Event-driven Run monitors that print current Agent state directly at the REPL
  and can redraw live Run, Agent, model, Tool, and Agent-form activity as a
  terminal tree with elapsed time and cumulative model usage. `monitor-state`
  exposes the underlying Clojure data explicitly.
- A live Coding Agent that inspects repositories with local Tools, chooses its
  own strategy, edits code, and verifies the result.
- A packaged Harbor evaluation that runs the fixed Coding Agent on an isolated
  repository task and records its verifier reward and ATIF trajectory.

### Changed

- Runnable examples now live in task-scoped folders outside library source and Harbor
  builds a separate application artifact; library artifacts contain only the
  harness and production CLI.
- Documentation now introduces the harness from ordinary Agent use outward,
  separates prompt input from local context and Tool schemas, and presents the
  homoiconic motivation after the programming model.
- The harness now owns the model/tool loop; provider transports only translate
  model I/O.
- Orchestration is normal Clojure control flow plus `run!`, rather than a
  separate workflow or child-call API.
- Model-visible `:instructions` is distinct from local `:context`; local
  context is never exposed automatically.
- The packaged `system.md` prompt is prepended automatically; Agent
  `:instructions` extend it.
- Generated Agents receive only the explicit `input` in the `agent` Tool call,
  never the parent model's conversation or Session history.
- Conversation history follows the established Session abstraction. Runs are
  stateless unless supplied a Session; `memory-session` is the process-local
  implementation and applications may provide durable implementations.
- Loop controls are top-level Agent options: `:max-turns` and `:stop-when`.
- Agent input contracts may be paired with a model-facing `:input-schema` when
  the Agent is available to another Agent.
- Run limits use Lisp-native vocabulary: `:depth` bounds nested Agent calls and
  `:agent-forms` bounds submitted runtime Agent forms.

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
- Public `as-tool` and the overloaded zero-argument `agent` form.

[Unreleased]: https://github.com/soohoonc/karcarthy

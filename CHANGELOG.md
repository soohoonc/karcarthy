# Changelog

All notable changes are documented here, following
[Keep a Changelog](https://keepachangelog.com/) and
[Semantic Versioning](https://semver.org/).

## Unreleased

### Added

- A native Clojure Agent/Tool harness with dynamic instructions, local context,
  optional Sessions, approvals, limits, cancellation, and events.
- Homoiconic `eval`: every model Agent can evaluate one ordinary Clojure
  expression that may create and run Agents.
- A complete, dynamically generated `eval` Tool manual covering the Clojure
  expression boundary, use cases, information boundary, execution behavior,
  and the model, Tool, and Agent symbols actually available.
- First-class `:agents`, which makes existing Agents callable through the
  established Agent-as-Tool pattern without introducing a hierarchy.
- Model-authored Clojure reading, full executable-position macroexpansion,
  same-process evaluation, recursive Agent execution, and eval events.
- A Responses-compatible HTTP/SSE transport with configurable endpoint,
  authentication environment, headers, model IDs, normalized streaming
  deltas, and a deterministic in-process mock model transport.
- Minimal local `read`, `write`, `edit`, `bash`, and ripgrep-backed `search`
  Tools plus generic `prompt` and `prompt-file` composition.
- Responses-hosted web search as an explicit endpoint capability.
- An MCP 2025-11-25 stdio client that discovers and adapts remote tools.
- An ACP v1 stdio server with sessions, permissions, cancellation, tool-call
  updates, streaming Agent-message chunks, per-session conversation history,
  session-provided stdio MCP servers, selectable model configuration, and
  aggregate prompt usage compatible with Harbor's ACP-to-ATIF conversion.
- An explicit paid live test that asks GPT-5.6 to author and run a concurrent
  Clojure workflow.
- A paid Agent test that inspects and edits a temporary directory.
- A minimal REPL chat example built from an Agent, `run!`, and a Session.
- Event-driven Run monitors that print current Agent state directly at the REPL
  and can redraw live Run, Agent, model, Tool, and eval activity as a
  terminal tree with elapsed time and cumulative model usage. `monitor-state`
  exposes the underlying Clojure data explicitly.
- A live Coding Agent that inspects repositories with local Tools, chooses its
  own strategy, edits code, and verifies the result.
- A packaged Harbor evaluation that runs the fixed Coding Agent on an isolated
  repository task and records its verifier reward and ATIF trajectory.

### Changed

- Agent and Tool configuration is flat on their maps; `assoc`, `update`, and
  destructuring work without a hidden `:config` layer.
- A model ID string is concise OpenAI Responses configuration; advanced model
  maps remain available.
- Renamed the deterministic test transport from `fake-model` to `mock-model`.
- Documentation now presents karcarthy as an executable argument for Clojure:
  a thesis-led overview, a complete Quickstart, outcome-driven examples,
  focused concepts and integrations, and mechanical reference pages.
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
- Agent `:instructions` are the complete model-visible instructions; the
  harness does not prepend a framework-owned system prompt.
- Agent calls made by eval receive only explicit input, never the parent model's
  conversation or Session history.
- Conversation history follows the established Session abstraction. Runs are
  stateless unless supplied a Session; `memory-session` is the process-local
  implementation and applications may provide durable implementations.
- Loop control is the top-level Agent option `:max-turns`.
- Agent and Tool boundaries now use one `:input-schema` and optional
  `:output-schema` for validation and model-facing JSON Schema derivation.
- Tool approval uses the established `:needs-approval` name; input and output
  guardrails use separate keys, and Run event callbacks use `:on-event`.
- Removed unused Tool retry, enabled, timeout, and metadata configuration and
  unused Agent and Run metadata configuration.
- The first `run!` establishes a dynamically scoped run. Calls within it,
  including calls in `future`, share its ID, usage, limits, events, context,
  deadline, cancellation, approvals, and executor.
- Run limits use Lisp-native vocabulary: `:depth` bounds participating Agent
  calls and `:evals` bounds evaluation attempts.
- Agent, Tool, Schema, and Run implementations now live in their direct
  namespaces instead of a monolithic internal core.

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
- Public Agent-form compiler functions and the restricted built-in `agent`
  Tool.
- The automatic packaged `system.md` prompt and public `system-prompt` helper.

[Unreleased]: https://github.com/soohoonc/karcarthy

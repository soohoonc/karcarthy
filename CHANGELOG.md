# Changelog

All notable changes are documented here, following
[Keep a Changelog](https://keepachangelog.com/) and
[Semantic Versioning](https://semver.org/).

## [Unreleased] — 0.0.2

Early and not yet released. What works so far:

### Added
- EDN data model for agents, with `clojure.spec` validation and the `defagent` /
  `defworkflow` macros.
- The `Runner` protocol and runners for offline mocks, Clojure functions,
  argv processes, shell commands, Claude CLI, Codex CLI, ACP agents, and the
  OpenAI Agents SDK.
- Runner-native subagents: `subagent` / `defsubagent` build delegation config
  for runners with native support (Claude Code subagents, OpenAI Agents SDK
  handoffs), and `karcarthy.runner.codex/subagent->config` lowers subagents to
  Codex custom-agent config maps (`agents->config` builds the global `[agents]`
  limits).
- Codex runner: `codex-runner` drives Codex CLI non-interactive mode
  (`codex exec`), sending the prompt on stdin.
- ACP runner: `acp-runner` speaks the Agent Client Protocol over stdio
  JSON-RPC; `connect!` / `close!` reuse one agent process and `initialize`
  across many runs via the `:connection` option.
- Workflow nodes interpreted by `run`: `pipe`, `step`, `branch`, `delegate`,
  `reduce`, `revise`, `route`, `continue`, and `dynamic`.
- Dynamic workflows (experimental — the op protocol and prompt format may
  change between releases): an agent can emit EDN ops (`:define`, `:patch`,
  `:remove`, `:call`, `:spawn`, `:complete`) during a run; `agent-ref` and
  `workflow-ref` resolve against that run's state.
- `karcarthy.self`: safe EDN parsing for agent-authored workflows and agents,
  plus the experimental `evolve` extension node for runtime
  instruction/model/tool patches.
- `karcarthy.rewrite`: structural workflow rewrites (`agents`, `over`,
  `configure`) that edit workflow EDN before `run` interprets it, without
  calling runners or `eval`.
- Observation events: an `:observe` run option receives OTel-shaped event maps
  for every workflow and agent span; observer errors never fail a run.
- `karcarthy.schema`: EDN and JSON schema reference values.
- `karcarthy.proc`: subprocess execution with `:timeout-ms` on every process
  runner; fault-isolated workflow nodes; bounded concurrency; tolerant routing.
- A `karcarthy` facade namespace re-exporting the common API under one alias.
- `karcarthy.cli`: a JSON bridge (workflow in, result out) so any language can
  drive karcarthy. Python and TypeScript examples included, plus a
  `COMPARISON.md` vs PydanticAI / Agno / Vercel AI SDK.
- Usage examples in Java, Kotlin, and Scala; MIT license; CI.
- JavaScript and Clojure examples for Claude dynamic-agent style workflows and
  OpenAI Deep Research request setup.

### Changed
- Workflow fields now have one declarative source of truth in
  `karcarthy.workflow`; recursive validation, public EDN/JSON schemas, and the
  CLI JSON allowlist are derived from it.
- Documentation dependencies were refreshed to current patch releases; the
  locked tree has a zero-advisory `npm audit`, and CI now audits, lints,
  type-checks, and builds the docs alongside the Clojure tests and JAR.
- `run` takes a single request map —
  `(run {:runner … :workflow … :input … :options …})` — instead of positional
  arguments.
- Model-facing EDN protocols self-repair: nodes that parse model replies
  (planner, evaluator, router, dynamic ops) re-ask the model with the error and
  its previous reply before failing, bounded by the `:edn-retries` run option
  (default 1; 0 fails fast).
- `subagent` options are driven by a single `subagent-option-keys` list:
  unknown options are rejected with `ex-info`, nil-valued options are omitted
  from the built map, and non-nil falsy values (e.g. `:background? false`) are
  kept.

### Removed
- The positional `(agent "name" "instructions" & opts)` arity; `agent` is
  map-only: `(agent {:name … :instructions …})`.
- The string form of `defagent`; only `(defagent sym {…})` remains.
- The agent runner registry: no more `{id -> Runner}` maps or per-agent
  `:runner` ids. Pass a `Runner` instance as `:runner` in the `run` request
  map; workflows stay plain data.

### Fixed
- ACP timeouts now send `session/cancel`, deny permission requests while
  cancellation is pending, and wait for a bounded grace period before killing
  the process. Capability-advertised sessions are closed after each turn.
- ACP authentication can select an advertised method before session creation,
  and additional image/audio/resource prompt blocks are validated against the
  agent's advertised prompt capabilities.
- Opt-in ACP client terminals now implement create, output, wait, kill, and
  release with bounded UTF-8-safe capture and automatic process cleanup.
- ACP wire encoding preserves namespaced `_meta` keys; session-scoped messages
  are checked against the active session; malformed stdout poisons reusable
  connections; missing filesystem parents are created; permission options
  without `kind` fail closed; and sequential config changes use refreshed,
  validated option lists.
- Successful OpenAI runner results now keep a consistent `:raw :runner`
  discriminator alongside the bridge payload.
- The CLI JSON bridge now passes the optional `"prompt"` field on `continue`
  nodes through to `karcarthy.orchestrate/continue`; it was declared in the
  published JSON schema but silently dropped.
- ACP spec-compliance fixes in `acp-runner`: `session/load` history replay no
  longer pollutes the returned `:text`; `stopReason` maps onto `:ok?` (only
  `end_turn` is success); session and fs capabilities are checked by value and
  gated independently; http/sse MCP server specs require the agent's
  `mcpCapabilities`; only `session/update` notifications are recorded in
  `:raw :updates`.

[Unreleased]: https://github.com/soohoonc/karcarthy

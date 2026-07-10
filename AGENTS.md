# karcarthy - guide for Codex

karcarthy is a native, homoiconic Clojure agent harness. It owns the model/tool
loop. Agents, tools, and orchestration are executable Clojure values and forms;
model-authored Agents are read, expanded, checked, evaluated, and run by the
same kernel.

There is no Runner protocol, EDN/JSON workflow DSL, or separate dynamic system.
Do not reintroduce `pipe`, `branch`, workflow nodes, runner adapters, or a JSON
workflow bridge.

## Commands

```bash
clojure -M:test
KARCARTHY_LIVE=1 OPENAI_API_KEY=... clojure -M:live-test
OPENAI_API_KEY=... clojure -M:examples basic "Say hello."
OPENAI_API_KEY=... clojure -M:examples coding /path/to/repo "Fix the failing tests."
clojure -M -m karcarthy.acp your.namespace/agent-var
clojure -T:build jar
cd docs && npm run lint && npm run types:check && npm run build
```

## Layout

| File | Role |
| --- | --- |
| `src/karcarthy.clj` | Public facade under one alias: `(require '[karcarthy :as k])`. |
| `src/karcarthy/core.clj` | Agent/Tool macros and values, contracts, model/tool loop, Session integration, limits, streaming events, approvals, and Runs. |
| `src/karcarthy/prompt.clj` | Generic instruction composition, prompt-file loading, and access to the packaged `system.md`. |
| `src/karcarthy/session.clj` | The conversation-history `Session` protocol and process-local `memory-session`. |
| `src/karcarthy/eval.clj` | Model-authored source reading, macroexpansion, evaluation, verification, and program events. |
| `src/karcarthy/model/responses.clj` | Complete and SSE-streaming Responses-compatible HTTP transport. It translates model I/O only. |
| `src/karcarthy/tools.clj` | Minimal `read` / `write` / `edit` / `bash` / `search` Tools rooted at a local directory. |
| `resources/karcarthy/system.md` | Readable system prompt packaged in library and standalone jars. |
| `resources/karcarthy/agent.md` | Model-facing manual for generating Agent programs; runtime model, Tool, and Agent catalogs are interpolated into it. |
| `src/karcarthy/mcp.clj` | MCP 2025-11-25 stdio client and MCP-to-Tool adapter. |
| `src/karcarthy/acp.clj` | ACP v1 stdio server, sessions, cancellation, tool updates, permissions, and session-provided MCP. |
| `examples/src/karcarthy/examples.clj` | Examples-only dispatcher for the live Basic and Coding Agents plus the REPL. |
| `src/karcarthy/cli.clj` | Minimal executable entry point; there is no JSON workflow command. |
| `test/karcarthy/core_test.clj` | Kernel, model loop, instructions/context, Sessions, streaming, composition, limits, and events. |
| `test/karcarthy/eval_test.clj` | Generated-form lifecycle and recursion. |
| `test/karcarthy/responses_test.clj` | Pure translation plus offline complete and SSE endpoint integration tests. |
| `test/karcarthy/tools_test.clj` | Local tools and generic prompt composition. |
| `test/karcarthy/mcp_test.clj` | MCP initialization, discovery, execution, and shutdown. |
| `test/karcarthy/acp_test.clj` | ACP session lifecycle, permissions, updates, and MCP bridging. |
| `test/karcarthy/live_test.clj` | Opt-in paid verification of the public Basic and Coding Agent examples. |

## Conventions

- **The harness owns semantics.** A model transport accepts one normalized
  request and returns `{:type :final ...}` or `{:type :tool-calls ...}`. A
  streaming transport may emit deltas before returning that authoritative
  response. It never executes tools, manages agents, or owns Sessions.
- **Keep the inner loop small.** Coding capabilities, hosted provider tools,
  MCP discovery, and ACP serving adapt to ordinary Tools around the kernel.
  Prompts must describe the capabilities actually installed.
- **Clojure is the orchestration language.** Use `let`, `if`, `case`,
  `loop/recur`, functions, macros, `future`, `deref`, and `run!`. Do not add a
  separate child-call or workflow API.
- **Bodies receive only input.** Agent and Tool bodies use `[input]`; internal
  Run machinery is dynamically scoped and must not become a public argument.
- **Agents and Tools retain code.** Preserve `:definition` and `:expansion`
  when changing macros or evaluation.
- **Agent generation is recursive.** `(agent config ...)` constructs an Agent.
  Every model Agent receives a dynamically documented `agent` Tool that reads,
  expands, evaluates, validates, and runs another ordinary Agent form. Its
  source and input are explicit; no parent conversation is inherited. Do not
  create a separate workflow representation or opt-in flag.
- **The base prompt is automatic.** `resources/karcarthy/system.md` is prepended
  to every model Agent. `:instructions` is an Agent-specific string or
  call-metadata function appended after it. Keep precise capability mechanics
  in Tool descriptions generated from the capabilities actually installed.
- **Instructions are model-visible; context is local.** `:context` is
  dependency injection and is never exposed automatically. Do not add
  request-mutation hooks such as `prepare-step`.
- **Conversation history belongs to a Session.** Runs are stateless unless the
  caller supplies `:session`. `memory-session` is process-local; durable stores
  implement `karcarthy.session/Session` outside the kernel. Do not call a
  conversation store a checkpoint or general workflow state.
- **Contracts fail closed.** Validate context, Agent input/output, and Tool
  input/output. Model/tool/protocol failures become structured failed Runs.
- **Generated code is intentionally evaluated.** Reader evaluation is disabled
  during the read phase, but checked forms are later evaluated as JVM Clojure.
  Full-trust evaluation is the default; do not replace it with an EDN
  interpreter in the name of safety.
- **Limits belong to a Run.** Agents entered through `:agents` or generated by
  a model consume that Run's budgets. A separate `run!` call creates a separate
  Run with separate limits and events.
- **Observation is part of the product.** New effects need stable event types
  and lineage.
- **Dependencies: Maven Central only.** HTTP uses Java's built-in client.
- Keep live/paid model tests opt-in. The normal suite must stay offline and
  deterministic through `fake-model`.
- Register new offline test namespaces in `test/karcarthy/test_runner.clj`;
  paid tests belong behind the `:live-test` alias and `KARCARTHY_LIVE=1`.

Contribution expectations: [CONTRIBUTING.md](CONTRIBUTING.md).

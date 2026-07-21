# karcarthy - guide for Codex

karcarthy is a native, homoiconic Clojure agent harness. It owns the model/tool
loop. Agents and Tools are flat data; orchestration is ordinary Clojure. A
model may evaluate one Clojure expression and call Agents during the same run.

There is no Runner protocol, EDN/JSON workflow DSL, or separate dynamic system.
Do not reintroduce `pipe`, `branch`, workflow nodes, runner adapters, or a JSON
workflow bridge.

## Commands

```bash
clojure -M:test
KARCARTHY_LIVE=1 OPENAI_API_KEY=... clojure -M:live-test
OPENAI_API_KEY=... clojure -M:examples basic "Say hello."
OPENAI_API_KEY=... clojure -M:examples coding /path/to/repo "Fix the failing tests."
KARCARTHY_LIVE=1 OPENAI_API_KEY=... examples/harbor/run.sh
clojure -M -m karcarthy.acp your.namespace/agent-var
clojure -T:build jar
cd docs && npm run lint && npm run types:check && npm run build
```

## Layout

| File | Role |
| --- | --- |
| `src/karcarthy.clj` | Public facade under one alias: `(require '[karcarthy :as k])`. |
| `src/karcarthy/agent.clj` | Direct Agent construction namespace. |
| `src/karcarthy/tool.clj` | Direct Tool construction namespace. |
| `src/karcarthy/run.clj` | Run participation, model/Tool loop, limits, context, and events. |
| `src/karcarthy/contract.clj` | Contracts and structured failures. |
| `src/karcarthy/prompt.clj` | Generic instruction composition, prompt-file loading, and rendering of the packaged eval Tool manual. |
| `src/karcarthy/session.clj` | The conversation-history `Session` protocol and process-local `memory-session`. |
| `src/karcarthy/eval.clj` | Same-process expression reading, macroexpansion, evaluation, and result normalization. |
| `src/karcarthy/model/responses.clj` | Complete and SSE-streaming Responses-compatible HTTP transport. It translates model I/O only. |
| `src/karcarthy/tools.clj` | Minimal `read` / `write` / `edit` / `bash` / `search` Tools rooted at a local directory. |
| `resources/karcarthy/eval.md` | Model-facing eval manual with model, Tool, and Agent catalogs. |
| `src/karcarthy/mcp.clj` | MCP 2025-11-25 stdio client and MCP-to-Tool adapter. |
| `src/karcarthy/acp.clj` | ACP v1 stdio server, sessions, cancellation, tool updates, permissions, and session-provided MCP. |
| `examples/main.clj` | Small command dispatcher for the live Basic and Coding examples and the REPL. |
| `examples/basic/main.clj` | Minimal live Agent example. |
| `examples/coding/main.clj` | Open-ended live Coding Agent. |
| `examples/harbor/main.clj` | Fixed Coding Agent packaged for Harbor evaluation through ACP. |
| `examples/harbor/run.sh` | Opt-in live Harbor evaluation of the bundled scheduler task. |
| `src/karcarthy/cli.clj` | Minimal executable entry point; there is no JSON workflow command. |
| `test/karcarthy/run_test.clj` | Model loop, instructions/context, Sessions, streaming, composition, limits, and events. |
| `test/karcarthy/eval_test.clj` | Dynamic Clojure workflows, concurrency, recursion, and eval boundaries. |
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
  MCP discovery, and ACP serving adapt to ordinary Tools around the loop.
  Prompts must describe the capabilities actually installed.
- **Clojure is the orchestration language.** Use `let`, `if`, `case`,
  `loop/recur`, functions, macros, `future`, `deref`, and `run!`. Do not add a
  separate child-call or workflow API.
- **Agents are model-backed.** `agent` and `defagent` accept configuration, not
  Clojure bodies. Use ordinary functions, macros, and data for orchestration.
- **Tool bodies receive only input.** Tool bodies use `[input]`; internal Run
  machinery is dynamically scoped and must not become a public argument.
- **Agents and Tools retain code.** Preserve `:definition` and `:expansion`
  when changing macros or evaluation.
- **Eval is recursive.** Every model Agent receives a dynamically documented
  `eval` Tool for one Clojure expression. The expression may use normal control
  flow, construct Agents and Tools, and call `run!`; no parent conversation is
  inherited by those Agent calls. Do not create a workflow representation or
  opt-in flag.
- **Instructions are exact.** `:instructions` is the complete model-visible
  string or a call-metadata function returning it. The harness does not prepend
  a framework prompt. Keep capability mechanics in Tool descriptions generated
  from the capabilities actually installed.
- **Instructions are model-visible; context is local.** `:context` is
  dependency injection and is never exposed automatically. Do not add
  request-mutation hooks such as `prepare-step`.
- **Conversation history belongs to a Session.** Runs are stateless unless the
  caller supplies `:session`. `memory-session` is process-local; durable stores
  implement `karcarthy.session/Session` outside the harness. Do not call a
  conversation store a checkpoint or general workflow state.
- **Contracts fail closed.** Validate context, Agent input/output, and Tool
  input/output. Model/tool/protocol failures become structured failed Runs.
- **Model-authored code is intentionally evaluated.** Reader evaluation is disabled
  during the read phase, but the expression is later evaluated as JVM Clojure.
  Full-trust evaluation is the default; do not replace it with an EDN
  interpreter in the name of safety.
- **The first `run!` establishes the run.** Every call in its dynamic extent,
  including calls in `future`, shares its budgets and events. A `run!` call
  outside that extent establishes another run.
- **Observation is part of the product.** New effects need stable event types
  and lineage.
- **Dependencies: Maven Central only.** HTTP uses Java's built-in client.
- Keep live/paid model tests opt-in. The normal suite must stay offline and
  deterministic through `mock-model`.
- Register new offline test namespaces in `test/karcarthy/test_runner.clj`;
  paid tests belong behind the `:live-test` alias and `KARCARTHY_LIVE=1`.

Contribution expectations: [CONTRIBUTING.md](CONTRIBUTING.md).

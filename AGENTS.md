# karcarthy - guide for Codex

karcarthy is a native, homoiconic Clojure agent harness. It owns the model/tool
loop. Agents, tools, and orchestration are executable Clojure values and forms;
model-authored Agents are read, expanded, checked, evaluated, and invoked by
the same Runtime.

There is no Runner protocol, EDN/JSON workflow DSL, or separate dynamic system.
Do not reintroduce `pipe`, `branch`, workflow nodes, runner adapters, or a JSON
workflow bridge.

## Commands

```bash
clojure -M:test
KARCARTHY_LIVE=1 OPENAI_API_KEY=... clojure -M:live-test
clojure -M -m karcarthy.demo
clojure -M -e '(load-file "examples/clojure/generated_calculator.clj")'
clojure -M -m karcarthy.acp your.namespace/agent-var
clojure -T:build jar
cd docs && npm run lint && npm run types:check && npm run build
```

## Layout

| File | Role |
| --- | --- |
| `src/karcarthy.clj` | Public facade under one alias: `(require '[karcarthy :as k])`. |
| `src/karcarthy/core.clj` | Recursive Agent/Tool macros and values, contracts, Runtime, native model/tool loop, child execution, limits, memory, approvals, events, and Runs. |
| `src/karcarthy/eval.clj` | Model-authored source reading, macroexpansion, evaluation, verification, and program events. |
| `src/karcarthy/model/responses.clj` | Responses-compatible HTTP transport for OpenAI or compatible gateways. It translates model I/O only. |
| `src/karcarthy/tools.clj` | Minimal `read` / `write` / `edit` / `bash` / `search` Tools rooted at a workspace. |
| `src/karcarthy/prompt.clj` | Renders capability-derived workspace system instructions. |
| `resources/karcarthy/system.md` | Readable system-prompt template packaged in library and standalone jars. |
| `src/karcarthy/mcp.clj` | MCP 2025-11-25 stdio client and MCP-to-Tool adapter. |
| `src/karcarthy/acp.clj` | ACP v1 stdio server, sessions, cancellation, tool updates, permissions, and session-provided MCP. |
| `src/karcarthy/demo.clj` | Offline fake-model/tool-loop demonstration. |
| `src/karcarthy/cli.clj` | Minimal executable entry point; there is no JSON workflow command. |
| `test/karcarthy/core_test.clj` | Kernel, model loop, tools, contracts, composition, limits, memory, and events. |
| `test/karcarthy/eval_test.clj` | Generated-form lifecycle and recursion. |
| `test/karcarthy/responses_test.clj` | Pure translation plus an offline compatible-endpoint integration test. |
| `test/karcarthy/tools_test.clj` | Workspace tools and capability-derived prompt. |
| `test/karcarthy/mcp_test.clj` | MCP initialization, discovery, execution, and shutdown. |
| `test/karcarthy/acp_test.clj` | ACP session lifecycle, permissions, updates, and MCP bridging. |
| `test/karcarthy/live_test.clj` | Opt-in paid OpenAI test of recursive `(agent)` generation. |

## Conventions

- **The harness owns semantics.** A model transport accepts one normalized
  request and returns `{:type :final ...}` or `{:type :tool-calls ...}`. It
  never executes tools, manages agents, or owns memory.
- **Keep the inner loop small.** Coding capabilities, hosted provider tools,
  MCP discovery, and ACP serving adapt to ordinary Tools around the kernel.
  Prompts must describe the capabilities actually installed.
- **Clojure is the orchestration language.** Use `let`, `if`, `case`,
  `loop/recur`, functions, macros, `invoke!`, and structured child execution.
- **Agents and Tools retain code.** Preserve `:source-form` and
  `:expanded-form` when changing macros or evaluation.
- **`agent` is recursive.** `(agent config ...)` constructs an Agent;
  zero-arity `(agent)` is the model-facing tool that accepts and runs another
  ordinary Agent form. Do not create a separate dynamic/expansion primitive.
- **Contracts fail closed.** Validate context, Agent input/output, and Tool
  input/output. Model/tool/protocol failures become structured failed Runs.
- **Generated code is intentionally evaluated.** Reader evaluation is disabled
  during the read phase, but checked forms are later evaluated as JVM Clojure.
  Full-trust evaluation is the default; do not replace it with an EDN
  interpreter in the name of safety.
- **Limits are shared.** Recursive children consume root model, token,
  generated-form, depth, concurrency, cancellation, and deadline budgets.
- **Observation is part of the product.** New effects need stable event types
  and lineage.
- **Dependencies: Maven Central only.** HTTP uses Java's built-in client.
- Keep live/paid model tests opt-in. The normal suite must stay offline and
  deterministic through `fake-model`.
- Register new offline test namespaces in `test/karcarthy/test_runner.clj`;
  paid tests belong behind the `:live-test` alias and `KARCARTHY_LIVE=1`.

Contribution expectations: [CONTRIBUTING.md](CONTRIBUTING.md).

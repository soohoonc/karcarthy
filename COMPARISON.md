# karcarthy vs. other agent builders

Most agent frameworks define agents, tools, and workflows as **objects or
decorators in a host language**, and bundle the agent loop (model call to tool
call to model, until done). karcarthy makes a different pair of choices: the
workflow is **plain data** (EDN), and the loop is **delegated to an Agent SDK,
agent framework, coding-agent CLI, or command adapter**.
This page compares the common patterns and is honest about the trade-offs.

The frameworks referenced:

- **PydanticAI** ("pi") — type-safe Python agents, model-agnostic, the "FastAPI
  feeling." ([pydantic-ai](https://github.com/pydantic/pydantic-ai))
- **DSPy** — declarative LM programs built from typed signatures, modules,
  metrics, and optimizers that compile prompts or weights.
  ([dspy](https://dspy.ai/))
- **Agno** — a high-performance multi-agent runtime with sessions, memory,
  knowledge, MCP, and a prebuilt server (AgentOS). ([agno](https://github.com/agno-agi/agno))
- **Vercel AI SDK** — TypeScript, optimized for streaming to a web UI, tool
  calling, and conversation state. ([ai-sdk](https://ai-sdk.dev/))
- (Also in this space: OpenAI Agents SDK, LangGraph, CrewAI, Mastra.)

## Common patterns, side by side

| Pattern | PydanticAI / DSPy / Agno / Vercel AI SDK | karcarthy |
| --- | --- | --- |
| Define an agent | a host-language object/module: `Agent(model, instructions, tools)`, a DSPy `Signature` + module, or `{ model, tools }` (TS) | a data map: `{:karcarthy/type :agent :name … :instructions … :model …}` |
| The agent loop | the framework runs it for you | delegated to the `claude` CLI, OpenAI Agents SDK, a local model, or another adapter |
| Tools | typed functions: Pydantic models / Zod schemas / Python callables; DSPy `ReAct` can use tools | a tool allowlist handed to the selected SDK/CLI, when that system supports it |
| Multi-agent | teams (Agno), handoffs (OpenAI), graphs (LangGraph) | functional workflow data: `pipe`, `map`, `reduce`, `iterate`, `bind` |
| Structured output | Pydantic model / Zod / `generateObject`; DSPy signatures type input/output fields | parse the reply yourself (roadmap: typed signatures / JSON Schema) |
| Optimization | DSPy optimizers compile programs against metrics; others mostly leave prompt tuning to the application | no optimizer yet; workflows are easy to transform because they are data |
| Streaming | tokens/events, especially to a UI | Claude CLI streaming hooks today |
| Sessions / memory | built in (Agno AgentOS, PydanticAI) | adapter/session state is delegated; richer memory is out of scope today |
| Observability | Logfire, AgentOS, DSPy tracing/debugging, etc. | OTel-compatible event maps via `:observe` |

## What karcarthy does differently

- **The workflow is data.** You build it with functions or write it by hand,
  then inspect, serialize, diff, generate, and transform it with ordinary code
  (`clojure.walk`, etc.). The plan is a value, not a callgraph hidden in objects.
- **The loop is delegated.** karcarthy doesn't reimplement the model/tool loop;
  it drives an Agent SDK, agent framework, coding-agent CLI, or command process.
  That makes it provider-neutral and thin, and it means karcarthy can sit *on
  top of* the others: a Pydantic AI or Agno agent can be wrapped through the
  `command` or `openai` adapter, or a small shim.
- **The runtime state is an intermediate representation.** DSPy is strong
  evidence for separating *what* a module should do from *how* its prompt or
  weights are tuned. karcarthy applies the same pressure one level up: keep
  agents, workflows, and operation history as data that Agent SDKs, CLIs, and
  adapters can execute while ordinary code can rewrite it.
- **Agents can emit orchestration data.** Because workflows and runtime
  operations are data parsed with `clojure.edn` (never `eval`), advanced APIs can
  parse model output into workflows or patches and feed them into the same
  runtime state without treating text as code.
  Most frameworks let the model call tools and hand off; they don't usually make
  the orchestration state itself data the model emits and edits.
- **It's language-neutral.** Because the unit of exchange is data, you can drive
  it from any language over a JSON bridge — see the Python and TypeScript
  examples in [`examples/`](examples/), no Clojure required.

## When to use what

- Typed tools, structured output, great Python DX: **PydanticAI**.
- Declarative LM programs you want to optimize against examples and metrics:
  **DSPy**.
- A batteries-included multi-agent runtime with memory, knowledge, a server, and
  observability: **Agno**.
- A web app streaming to React: **Vercel AI SDK**.
- Orchestration you want as inspectable, generatable, serializable data;
  provider-neutral; on the JVM/Lisp; or where agents generate and rewrite
  workflows themselves: **karcarthy** (early and minimal, and able to drive the
  others through SDK/CLI adapters).

These aren't strictly either/or: karcarthy is a thin coordination layer, and the
frameworks above are good candidates to run *inside* it as SDK/CLI adapters.

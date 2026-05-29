# karcarthy vs. other agent builders

Most agent frameworks define agents, tools, and workflows as **objects or
decorators in a host language**, and bundle the agent loop (model call to tool
call to model, until done). karcarthy makes a different pair of choices: the
workflow is **plain data** (EDN), and the loop is **delegated to a runner**.
This page compares the common patterns and is honest about the trade-offs.

The frameworks referenced:

- **PydanticAI** ("pi") — type-safe Python agents, model-agnostic, the "FastAPI
  feeling." ([pydantic-ai](https://github.com/pydantic/pydantic-ai))
- **Agno** — a high-performance multi-agent runtime with sessions, memory,
  knowledge, MCP, and a prebuilt server (AgentOS). ([agno](https://github.com/agno-agi/agno))
- **Vercel AI SDK** — TypeScript, optimized for streaming to a web UI, tool
  calling, and conversation state. ([ai-sdk](https://ai-sdk.dev/))
- (Also in this space: OpenAI Agents SDK, LangGraph, CrewAI, Mastra.)

## Common patterns, side by side

| Pattern | PydanticAI / Agno / Vercel AI SDK | karcarthy |
| --- | --- | --- |
| Define an agent | a host-language object: `Agent(model, instructions, tools)` (Py) or `{ model, tools }` (TS) | a data map: `{:karcarthy/type :agent :name … :instructions … :model …}` |
| The agent loop | the framework runs it for you | delegated to a **runner** (the `claude` CLI, OpenAI Agents SDK, a local model) |
| Tools | typed functions: Pydantic models / Zod schemas / Python callables | a tool allowlist handed to the runner (the runner executes them; MCP via the runner) |
| Multi-agent | teams (Agno), handoffs (OpenAI), graphs (LangGraph) | workflow nodes as data: `chain`, `parallel`, `route`, `refine`, `orchestrate`, `handoff` |
| Structured output | Pydantic model / Zod / `generateObject` | parse the reply yourself (roadmap: `--json-schema`) |
| Streaming | tokens/events, especially to a UI | `claude-cli` runner `:on-event` |
| Sessions / memory | built in (Agno AgentOS, PydanticAI) | `converse` / `:resume` for sessions; richer memory is delegated or out of scope |
| Observability | Logfire, AgentOS, etc. | none built in |

## What karcarthy does differently

- **The workflow is data.** You build it with functions or write it by hand,
  then inspect, serialize, diff, generate, and transform it with ordinary code
  (`clojure.walk`, etc.). The plan is a value, not a callgraph hidden in objects.
- **The loop is delegated.** karcarthy doesn't reimplement the model/tool loop;
  it drives a runner. That makes it provider-neutral and thin, and it means
  karcarthy can sit *on top of* the others: a PydanticAI or Agno agent can be
  wrapped as a runner (via the `command` or `openai` adapter, or a small shim).
- **Agents can author and edit the workflow at runtime.** Because workflows are
  data parsed with `clojure.edn` (never `eval`), an agent can write a workflow that
  karcarthy runs (`run-authored`) or rewrite its own definition (`evolve`). Most
  frameworks let the model call tools and hand off; they don't usually make the
  orchestration itself data the model emits and edits.
- **It's language-neutral.** Because the unit of exchange is data, you can drive
  it from any language over a JSON bridge — see the Python and TypeScript
  examples in [`examples/`](examples/), no Clojure required.

## When to use what

- Typed tools, structured output, great Python DX: **PydanticAI**.
- A batteries-included multi-agent runtime with memory, knowledge, a server, and
  observability: **Agno**.
- A web app streaming to React: **Vercel AI SDK**.
- Orchestration you want as inspectable, generatable, serializable data;
  provider-neutral; on the JVM/Lisp; or where agents author and rewrite
  workflows themselves: **karcarthy** (early and minimal, and able to drive the
  others as runners).

These aren't strictly either/or: karcarthy is a thin coordination layer, and the
frameworks above are good candidates to run *inside* it as runners.

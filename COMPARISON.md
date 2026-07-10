# Configuration study

karcarthy borrows the strongest agent-configuration primitives from current
harnesses while making executable Clojure the orchestration language.

## Systems studied

| System | Useful configuration and runtime primitives | What karcarthy changes |
| --- | --- | --- |
| [Pi coding agent](https://github.com/badlogic/pi-mono/tree/main/packages/coding-agent) | A deliberately small loop, four default local tools, a system prompt assembled from enabled tools, project instruction files, and extensions outside the core. | Use the same small-kernel discipline for ordinary Agents, while keeping local Tools and prompt construction separate and adding homoiconic Agent generation, contracts, events, MCP, and ACP. |
| [Claude Code tools](https://code.claude.com/docs/en/tools-reference) and [OpenAI Codex](https://github.com/openai/codex) | Tool schemas and policy-bearing instructions around file inspection, editing, shell execution, search, delegation, and verification. | Preserve the capability-derived behavioral invariants without copying a private prompt or recreating every product-specific tool. |
| [Vercel AI SDK](https://ai-sdk.dev/docs/reference/ai-sdk-core/tool-loop-agent) | Model, instructions, typed tools, tool choice, active tools, structured output, stop conditions, per-step preparation, context, callbacks, retries, and timeouts. | Own the same loop controls in Clojure and allow an Agent to replace the default loop with an arbitrary program body. |
| [Vercel Workflow](https://useworkflow.dev/docs/foundations/how-it-works) | Durable Runs reconstructed from an event log of steps, waits, retries, and external events. | Keep this vocabulary separate. karcarthy currently has Agent Runs and conversation Sessions, not durable workflow checkpoints. |
| [Eve](https://eve.dev/docs/agent-config) | Model and reasoning settings, dynamic resolution, compaction, budgets, typed tools, approval, output schemas, subagents, sandbox, and instrumentation. Its [Workflow tool](https://eve.dev/docs/guides/dynamic-workflows) runs model-authored JavaScript that coordinates subagents. | Make generated executable code the central model rather than an optional root-only tool, and let generated Clojure use the complete harness API recursively. |
| [OpenAI Agents SDK](https://openai.github.io/openai-agents-js/guides/agents/) | Dynamic instructions and prompts, typed local context, tools and MCP, structured output, guardrails, tool-use behavior, agents as tools, handoffs, Sessions, and lifecycle hooks. | Preserve its established distinction between local context, model-visible instructions, and conversation Sessions, but express larger programs directly in Clojure. |
| [OpenAgents](https://openagents.org/docs/en/sdk/building-agents) | Event-driven lifecycle hooks, typed event context, channels, messages, files, discovery, and network mods. | Borrow the event vocabulary and external collaboration boundary; do not treat OpenAgents as the inner LLM harness. |

## Resulting configuration surface

An Agent may configure:

- identity: `:name`, `:description`;
- cognition: `:model`, `:instructions`;
- dependencies: `:context`;
- contracts: `:input`, `:output`;
- capabilities: `:tools`;
- loop policy: `:max-turns`, `:stop-when`;
- safety: `:guardrails`, approvals, and limits;
- observation: Run events and an `:observe` callback;
- behavior: an optional arbitrary Clojure body.

Tools are first-class values with input/output contracts, approval, enablement,
guardrails, timeouts, and a Clojure implementation. Agents become tools through
`as-tool`. Conversation history is a `Session` supplied to `run!`, not Agent
configuration or generic workflow state. True handoffs are not implemented.

## Vocabulary decisions

The OpenAI and Vercel implementations make three different lifetimes explicit:

| Name | Lifetime in karcarthy |
| --- | --- |
| Instructions | Model-visible policy resolved for one Agent invocation. |
| Context | Local application dependencies carried by one Runtime tree. |
| Session | Ordered conversation items shared across root Agent Runs. |
| Run | One execution result with output, usage, events, or failure. |

OpenAI's
[Sessions](https://openai.github.io/openai-agents-js/guides/sessions/) are
conversation stores, while its serializable
[Run state](https://github.com/openai/openai-agents-js/blob/main/packages/agents-core/src/runState.ts)
represents an interrupted execution. Vercel AI SDK leaves
[chat persistence](https://ai-sdk.dev/docs/ai-sdk-ui/chatbot-message-persistence)
to the application, and Vercel Workflow uses an event log to reconstruct a
durable Run. karcarthy implements the first concern through `Session`; it does
not rename conversation history to state or claim durable checkpointing.

Likewise, `as-tool` accurately describes a child call that returns control to
the parent. The word “handoff” is reserved for a future operation with actual
control and history transfer semantics.

## The essential difference

Most harnesses expose a configuration object around a fixed loop. Eve's
generated JavaScript workflow is the closest precedent, but it deliberately
limits the program to bridged subagent functions.

karcarthy treats the program itself as the configurable object:

```clojure
(agent config [rt input]
  ;; arbitrary Clojure, including construction and evaluation of new Agents
  ...)
```

The source form, expanded form, contracts, and execution events are retained.
That makes program transformations directly usable as experimental variables.

## Optimization and verification

For task `τ`, program `p`, local context `c`, Session history `h`, and
stochastic event sequence `ξ`:

```text
(y, h', ξ) ~ Exec(p, τ, c, h)
```

An optimizer may search source forms for reward under cost and latency limits:

```text
p* = argmaxₚ E[R(τ, ξ) - λ·cost(ξ) - μ·latency(ξ)]
```

Improvement is not required. The same apparatus may verify invariants, compare
behavioral distributions, characterize failure modes, or reproduce an agent's
generated program. Harbor supplies evaluation tasks through ACP; karcarthy
supplies the executable program and event record.

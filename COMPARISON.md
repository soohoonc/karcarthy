# Configuration study

karcarthy borrows the strongest agent-configuration primitives from current
harnesses while making executable Clojure the orchestration language.

## Systems studied

| System | Useful configuration and runtime primitives | What karcarthy changes |
| --- | --- | --- |
| [Pi coding agent](https://github.com/badlogic/pi-mono/tree/main/packages/coding-agent) | A deliberately small loop, four default local tools, a system prompt assembled from enabled tools, project instruction files, and extensions outside the core. | Use the same small-kernel discipline for ordinary Agents, while keeping workspace Tools and prompt construction separate and adding homoiconic Agent generation, contracts, traces, MCP, and ACP. |
| [Claude Code tools](https://code.claude.com/docs/en/tools-reference) and [OpenAI Codex](https://github.com/openai/codex) | Tool schemas and policy-bearing instructions around file inspection, editing, shell execution, search, delegation, and verification. | Preserve the capability-derived behavioral invariants without copying a private prompt or recreating every product-specific tool. |
| [Vercel AI SDK](https://ai-sdk.dev/docs/reference/ai-sdk-core/tool-loop-agent) | Model, instructions, typed tools, tool choice, active tools, structured output, stop conditions, per-step preparation, context, callbacks, retries, and timeouts. | Own the same loop controls in Clojure and allow an Agent to replace the default loop with an arbitrary program body. |
| [Eve](https://eve.dev/docs/agent-config) | Model and reasoning settings, dynamic resolution, compaction, budgets, typed tools, approval, output schemas, subagents, sandbox, and instrumentation. Its [Workflow tool](https://eve.dev/docs/guides/dynamic-workflows) runs model-authored JavaScript that coordinates subagents. | Make generated executable code the central model rather than an optional root-only tool, and let generated Clojure use the complete harness API recursively. |
| [OpenAI Agents SDK](https://openai.github.io/openai-agents-js/guides/agents/) | Dynamic instructions and prompts, typed local context, tools and MCP, structured output, guardrails, tool-use behavior, agents as tools, handoffs, sessions, and lifecycle hooks. | Preserve the useful distinction between local context and model-visible context, but express larger programs directly in Clojure rather than through a manager/handoff-only loop. |
| [OpenAgents](https://openagents.org/docs/en/sdk/building-agents) | Event-driven lifecycle hooks, typed event context, channels, messages, files, discovery, and network mods. | Borrow the event vocabulary and external collaboration boundary; do not treat OpenAgents as the inner LLM harness. |

## Resulting configuration surface

An Agent may configure:

- identity: `:name`, `:description`;
- cognition: `:model`, `:instructions`;
- dependencies: `:context`;
- contracts: `:input`, `:output`;
- capabilities: `:tools`;
- loop policy: `:loop`;
- state: `:memory`;
- safety: `:guardrails`, approvals, and limits;
- observation: `:hooks`;
- behavior: an optional arbitrary Clojure body.

Tools are first-class values with input/output contracts, approval, enablement,
guardrails, timeouts, and a Clojure implementation. Agents become tools through
`as-tool`; conversation transfer uses `handoff!`.

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

The source form, expanded form, contracts, and execution trace are retained.
That makes program transformations directly usable as experimental variables.

## Optimization and verification

For task `τ`, program `p`, and stochastic trace `ξ`:

```text
ξ ~ Exec(p, τ)
```

An optimizer may search source forms for reward under cost and latency limits:

```text
p* = argmaxₚ E[R(τ, ξ) - λ·cost(ξ) - μ·latency(ξ)]
```

Improvement is not required. The same apparatus may verify invariants, compare
behavioral distributions, characterize failure modes, or reproduce an agent's
generated program. Harbor supplies evaluation tasks through ACP; karcarthy
supplies the executable program and trace.

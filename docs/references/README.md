# References

These are the protocol and SDK surfaces that informed the proposed karcarthy
runtime vocabulary.

## Agent Client Protocol

- [Session setup](https://agentclientprotocol.com/protocol/v1/session-setup)
  defines sessions as conversation/thread state, including context, history, and
  MCP servers passed at session creation or load time.
- [Prompt turn](https://agentclientprotocol.com/protocol/v1/prompt-turn) defines
  the main user-turn lifecycle: `session/prompt`, `session/update`, tool call
  updates, stop reasons, and cancellation.
- [Tool calls](https://agentclientprotocol.com/protocol/v1/tool-calls) describes
  how agents report tool execution progress and results to clients.
- [Session config options](https://agentclientprotocol.com/protocol/v1/session-config-options)
  supersedes older session modes and exposes session-level selectors such as
  model, mode, and reasoning level.
- [Session modes](https://agentclientprotocol.com/protocol/v1/session-modes) is the
  older mode API; it is useful historical context, not a karcarthy surface.

Takeaways for karcarthy:

- `context` should be session/message state, not a broad resource kind.
- `:mode` should not be a core resource. It belongs in session config or
  SDK/CLI adapter options.
- tool calls are runtime events, not resource definitions.
- permission requests are protocol adapter behavior, not graph IR.

## OpenAI Agents SDK and Responses API

- [Using tools](https://developers.openai.com/api/docs/guides/tools) describes
  hosted tools, function tools, remote MCP tools, and Agents SDK usage.
- [Tool search](https://developers.openai.com/api/docs/guides/tools-tool-search)
  supports deferred loading of tools through namespaces or MCP servers.
- [Orchestration and handoffs](https://developers.openai.com/api/docs/guides/agents/orchestration)
  distinguishes handoffs from agents-as-tools.
- [Running agents](https://developers.openai.com/api/docs/guides/agents/running-agents)
  describes the agent loop, sessions, server-managed continuation, and streaming.
- [Results and state](https://developers.openai.com/api/docs/guides/agents/results)
  separates final output from replay history, handoff ownership, continuation
  IDs, interruptions, and resumable state.
- [Integrations and observability](https://developers.openai.com/api/docs/guides/agents/integrations-observability)
  covers MCP wiring and traces for model calls, tools, handoffs, and guardrails.

Takeaways for karcarthy:

- MCP, hosted tools, and function tools should stay in SDK/provider config
  until karcarthy has a concrete reason to interpret them directly.
- `map` can target agents-as-tools, multiple SDK runs, or graph branches.
- final output, emitted updates, and resumable state should stay separate.
- mutable runtime state should preserve enough result data for replay and
  continuation.

## Claude Agent SDK and Claude Code

- [MCP in the SDK](https://code.claude.com/docs/en/agent-sdk/mcp) shows MCP
  servers configured in code, `.mcp.json`, or HTTP/SSE transports with allowed
  tool filters.
- [Subagents in the SDK](https://code.claude.com/docs/en/agent-sdk/subagents)
  defines programmatic subagents with descriptions, prompts, tool restrictions,
  and context isolation.
- [Claude Code subagents](https://code.claude.com/docs/en/sub-agents) shows
  filesystem agent definitions, scoped MCP servers, permission modes, preloaded
  skills, and parallel research.
- [Model Context Protocol overview](https://claude.com/docs/connectors/building/mcp)
  frames MCP around tools, resources, prompts, local/remote servers, and security
  hints.

Takeaways for karcarthy:

- subagents provide context isolation and return only final messages to parents;
  this supports explicit `context` passing and `map`/`reduce` synthesis.
- MCP servers should be scoping boundaries so tool definitions do not bloat the
  parent context unnecessarily.
- permission modes are part of the host/runtime adapter, not first-class graph
  operations.

## DSPy

- [DSPy](https://dspy.ai/) emphasizes typed signatures, modules, metrics, and
  optimizers.

Takeaways for karcarthy:

- prompts should not be the only contract; agents and graphs should grow typed
  input/output signatures once validation needs them.
- metrics and optimization can help the agent patch itself, but they should not
  expand the runtime surface before they have interpreter behavior.

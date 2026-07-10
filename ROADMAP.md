# Roadmap

karcarthy now implements the native Clojure harness described in the docs. The
Runner protocol, EDN/JSON workflow interpreter, workflow nodes, schemas, JSON
bridge, and legacy examples have been removed without a compatibility layer.

## Implemented kernel

- Executable Agent and Tool values retaining source and expanded forms.
- Recursive `agent`—constructor with a config, model-facing Agent capability
  with no arguments—plus `defagent`, `tool`, and `deftool`.
- Native model/tool loop with local tool execution and repair through ordinary
  model turns.
- Responses-compatible HTTP transport and an offline fake transport.
- Bounded local file/search/process tools, generic prompt composition, a
  readable packaged system prompt, and hosted Responses tools.
- MCP 2025-11-25 stdio initialization, tool discovery, calls, and adaptation to
  ordinary Tools.
- ACP v1 stdio serving for an Agent or per-session Agent factory, including
  session-provided stdio MCP, tool updates, permissions, and cancellation.
- `run!`, `invoke!`, `spawn!`, `await!`, `await-all!`, and `as-tool`.
- Dynamic model instructions, separate local Runtime context, optional
  conversation Sessions, Clojure specs, JSON Schema derivation, guardrails,
  approval checks, cancellation, deadlines, hierarchical limits, and events.
- Complete and SSE Responses transport paths, normalized runtime deltas, and
  incremental ACP Agent-message chunks.
- `read-agent-form`, macroexpansion, evaluation, Agent verification, structured
  failures, recursive generation limits, and event lineage.

## Complete harness semantics

- Add durable approval/input suspension and resumption.
- Add observable compaction policies over Session history.
- Provide documented database-backed Session implementations.
- Add richer retry/backoff and idempotency policy for effects.
- Add true conversational handoffs only with defined history and control
  transfer semantics.

## Serve and evaluate

- Add ACP session loading/resumption and richer incremental child events.
- Add MCP Streamable HTTP after the stdio surface is proven in evaluation.
- Connect Harbor tasks and scorers.
- Store program hashes, source/expanded forms, event metrics, and evaluator
  results together.

## Research tooling

- Provide structural Clojure-form mutation helpers without creating a workflow
  DSL.
- Add replay and differential event comparison.
- Add search loops over program variants: random/evolutionary search, bandits,
  Bayesian optimization, or model-proposed rewrites.
- Treat behavioral verification and negative results as first-class outcomes.

## Non-goals

- No Runner compatibility layer.
- No EDN or JSON orchestration language.
- No separate dynamic-workflow feature.
- No reimplementation of Clojure control flow as node constructors.
- No requirement that a research experiment demonstrate improvement.

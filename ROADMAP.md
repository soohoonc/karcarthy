# Roadmap

karcarthy is a native Clojure agent harness. The implemented kernel includes:

- Agent and Tool values that retain definitions and expansions;
- a model and Tool loop with typed inputs, outputs, approvals, and events;
- Clojure Agent programs and ordinary `run!` composition;
- available Agents through `:agents`;
- recursive model-written Agents by default;
- Responses-compatible complete and streaming inference;
- local, hosted, and MCP Tools;
- conversation Sessions;
- ACP serving for editors and evaluation systems.

## Next

- durable suspension and resumption for approval and human-input boundaries;
- observable Session-history compaction;
- richer retry, backoff, and effect-idempotency policy;
- ACP session loading and resumption;
- MCP Streamable HTTP transport;
- publication of the locally verified ACP launcher in the ACP registry;
- program hashes and exportable event records.

## Non-goals

- no EDN or JSON orchestration language;
- no workflow-node interpreter;
- no separate dynamic-workflow feature;
- no second child-call API alongside `run!` and `:agents`.

# Roadmap

karcarthy is a native Clojure agent harness. The implemented library includes:

- Agent and tool values that retain definitions and expansions;
- a model and tool loop with typed inputs, outputs, approvals, and events;
- same-process Clojure eval and ordinary `run!` composition;
- available agents through `:agents`;
- recursively model-authored Clojure programs through the explicit `eval`
  capability;
- Responses-compatible complete and streaming inference;
- local, hosted, and MCP tools;
- conversation sessions;
- ACP serving for editors and evaluation systems.

## Next

- durable suspension and resumption for approval and human-input boundaries;
- observable session-history compaction;
- richer retry, backoff, and effect-idempotency policy;
- ACP session loading and resumption;
- MCP Streamable HTTP transport;
- publication of the locally verified ACP launcher in the ACP registry;
- eval-source hashes and exportable event records.

## Non-goals

- no EDN or JSON orchestration language;
- no workflow-node interpreter;
- no separate dynamic-workflow feature;
- no second child-call API alongside `run!` and `:agents`.

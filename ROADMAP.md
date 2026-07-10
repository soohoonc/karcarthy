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
- packaging and evaluation of the ACP launcher with Harbor tasks and scorers;
- program hashes and exportable event records for experiments.

## Research tooling

- structural Clojure-form mutation helpers;
- replay and differential event comparison;
- search over program variants;
- behavioral verification and negative-result reporting.

## Non-goals

- no EDN or JSON orchestration language;
- no workflow-node interpreter;
- no separate dynamic-workflow feature;
- no second child-call API alongside `run!` and `:agents`;
- no requirement that an experiment demonstrate improvement.

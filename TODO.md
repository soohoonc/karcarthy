# TODO

The native harness and homoiconic execution path are implemented. Immediate
follow-ups:

## Runtime

- Durable suspension/resumption for approvals and human input.
- Observable Session-history compaction.
- Streaming transport events.
- Retry/backoff and effect idempotency policy.
- Define true handoff control and history-transfer semantics before adding an
  API.

## Evaluation

- Package/register the ACP launcher and run Harbor benchmark tasks and scorers.
- ACP session loading/resumption and incremental model streaming.
- MCP Streamable HTTP transport.
- Event export containing program hashes, forms, usage, latency, and scores.

## Research

- Form mutation helpers.
- Replay and differential event comparison.
- Search drivers over program variants.

Direction and sequencing live in [ROADMAP.md](ROADMAP.md).

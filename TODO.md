# TODO

The native harness and homoiconic execution path are implemented. Immediate
follow-ups:

## Runtime

- Durable suspension/resumption for approvals and human input.
- Observable context compaction.
- Streaming transport events.
- Retry/backoff and effect idempotency policy.
- Conversational state transfer for `handoff!`.

## Evaluation

- Harbor smoke evaluation.
- ACP session loading/resumption and incremental model streaming.
- MCP Streamable HTTP transport.
- Trace export containing program hashes, forms, usage, latency, and scores.

## Research

- Form mutation helpers.
- Replay and differential trace comparison.
- Search drivers over program variants.

Direction and sequencing live in [ROADMAP.md](ROADMAP.md).

# Architect example

This live example shows the feature that distinguishes karcarthy: an Agent
writes another Agent, which writes and concurrently runs two more Agents.

```bash
clojure -M:examples architect \
  "Review a migration from synchronous writes to a queue."
```

The root calls `eval` to create a coordinator. The coordinator independently
calls `eval` to create two specialists and run them with `future`. Every Agent
call participates in the first run and shares its ID, limits, usage, events,
context, cancellation, approvals, and executor.

```text
Run run_7c2e9b… · running · 18s · 6 model calls · 8,421 tokens · 2 evals
└─ architect · waiting for Agent
   └─ coordinator · waiting for Agents
      ├─ failure-analyst · calling model
      └─ rollout-planner · calling model
```

Inspect `:eval/expanded` to see the expression and macroexpansion. Inspect
`:agent/started` to see the resulting call tree. Evaluation and coordination
stay in the same JVM; model transport calls still use their configured I/O.

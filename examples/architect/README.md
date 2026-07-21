# Architect example

This live example shows the feature that distinguishes karcarthy: an Agent
writes one Clojure expression that creates and concurrently runs two Agents.

```bash
clojure -M:examples architect \
  "Review a migration from synchronous writes to a queue."
```

The parent calls `eval` once. Its expression uses `let`, `agent`, `future`,
`run!`, `deref`, and normal collection operations. Both Agent calls participate
in the first run and share its ID, limits, usage, events, context, cancellation,
approvals, and executor.

```text
Run run_7c2e9b… · running · 18s · 3 model calls · 8,421 tokens · 1 eval
└─ architect · waiting for Agent
   ├─ failure-analyst · calling model
   └─ rollout-planner · calling model
```

Inspect `:eval/expanded` to see the expression and macroexpansion. Inspect
`:agent/started` to see the resulting call tree. Evaluation and coordination
stay in the same JVM; model transport calls still use their configured I/O.

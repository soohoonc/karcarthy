# Toward a production-ready default runtime

karcarthy should not have a separate static-vs-dynamic split. The default runtime
should treat agents, workflows, and execution history as data that the running
controller can change.

```text
runtime -> agents + workflows + history -> next operation -> evolved runtime
```

Claude, Codex, OpenAI Agents SDK, local models, and command runners still own the
inner agent loop. karcarthy owns the small mutable state layer around those
runners.

## What production-ready means

### 1. Versioned runtime state

- Add a top-level `:karcarthy/version`.
- Define schema for agents, workflows, operations, results, and history records.
- Support EDN and JSON readers/writers.
- Normalize equivalent operations into one canonical printed form.
- Add migrations between state versions.

### 2. Small operation set

The current target surface is:

- `:put`
- `:patch`
- `:remove`
- `:call`
- `:complete`

Legacy names remain compatibility aliases, but new docs and prompts should use
the smaller default vocabulary.

### 3. Durable history

In-memory state is enough for demos. Production needs:

- run IDs and operation IDs;
- append-only operation log;
- operation result records;
- state snapshots;
- replay from genesis or snapshot;
- fork from any operation;
- crash recovery;
- cancellation and deadlines;
- token, latency, and cost accounting.

### 4. Runtime self-editing

The controller should be able to create, patch, remove, and call agents and
workflows while it runs:

```clojure
{:op :put
 :resource {:kind :agent
            :id "critic"
            :instructions "Find flaws in the current plan."}}

{:op :patch
 :kind :agent
 :id "critic"
 :merge {:instructions "Find flaws and propose one better plan."}}

{:op :call
 :target "critic"
 :input "Review the current approach."}
```

Do not promote tools, integrations, memory, policies, or environments into new
runtime resource kinds until the interpreter has a concrete behavior for them.
For now:

- tools stay on agents/runners;
- MCP and provider integrations stay in runner config;
- context is passed as input/history;
- safety boundaries are host runtime concerns.

### 5. Typed signatures later

DSPy is useful inspiration, but signatures should be added only when they unlock
validation or reliable self-editing:

```clojure
{:kind :agent
 :id "researcher"
 :instructions "Research the question and cite sources."
 :signature {:inputs  {:question :string}
             :outputs {:answer :string
                       :citations [:vector :string]}}}
```

This is a future contract layer, not required for the first durable runtime.

### 6. Observability

OpenTelemetry instrumentation is already present. The next layer is a stable
event model for:

- operations;
- resource mutations;
- model calls;
- workflow calls;
- replay/fork events;
- cost, latency, token, and turn counters.

The trace should make it obvious why the agent changed itself.

### 7. Distribution

Before calling this production-ready:

- publish releases instead of requiring git SHAs;
- document compatibility guarantees for runtime state versions;
- add a CLI linter: `karcarthy validate state.edn`;
- add a small server mode for language-neutral callers;
- keep offline tests as the default gate and add opt-in live conformance tests.

## Non-goals

- Reimplementing every provider's agent loop.
- Adding resource kinds because other frameworks have similar nouns.
- Turning self-evolution into a PR-promotion workflow.
- Putting approval requests in the graph IR.
- Treating prompt strings as the stable production API.

The useful production shape is a small default runtime where the agent can
rewrite agents and workflows as data while the operation log records what
happened.

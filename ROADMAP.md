# Toward a production-ready self-evolving runtime

karcarthy should stay small: a data-first orchestration layer, not another
full-stack agent runtime. The production target is a durable runtime kernel where
agents, graphs, integrations, tools, environments, context, and history are all
data that the controller can rewrite while it runs.

This is different from a static configuration system. The deployed artifact is
the interpreter. The living state inside it evolves:

```text
runtime kernel -> agent state -> operation log -> evolved state -> next action
```

The useful outside inspiration from DSPy is still the separation between task
contracts, modules, metrics, and optimized state. karcarthy applies that idea at
the orchestration/runtime layer: the agent edits its own graph and specialists as
data, and the runtime records enough state to replay, fork, evaluate, and debug
what happened.

## What production-ready means

### 1. A versioned runtime state contract

- Add a top-level `:karcarthy/version`.
- Define schema for resource kinds: `:agent`, `:graph`, `:environment`,
  `:integration`, and `:tool`.
- Define schema for operation kinds: `:put`, `:patch`, `:remove`, `:call`,
  `:emit`, `:return`, and `:complete`.
- Provide canonical normalization so equivalent operations print the same way.
- Support EDN and JSON readers/writers with clear validation errors.
- Add migrations between state and operation versions.
- Separate portable runtime state from host-only extension points such as
  Clojure functions.

### 2. Durable operation logs and snapshots

In-memory interpretation is enough for demos. A production self-evolving runtime
needs:

- run IDs and operation IDs;
- append-only operation log;
- operation result records;
- periodic state snapshots;
- replay from genesis or from a snapshot;
- fork from any operation;
- crash recovery;
- cancellation and deadlines;
- resource accounting for model calls, tool calls, latency, tokens, and cost.

The log is the ground truth. Snapshots are acceleration.

### 3. Mutable runtime resources

The controller should be able to create, patch, remove, and call resources while
it runs:

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

The first implementation exists in `karcarthy.dynamic`. It now accepts the
living operation names while preserving the early `:define-agent`,
`:patch-agent`, `:run-agent`, and `:answer` aliases.

### 4. Capabilities and environments

Drop `:policy` as a core resource. It implies a gate. The runtime should model
what exists in the agent's world:

```clojure
{:kind :environment
 :id "default"
 :capabilities [:model :spawn-agent :patch-runtime :filesystem :network :mcp]
 :roots [{:type :filesystem :path "/repo"}
         {:type :runner :id :claude}
         {:type :mcp :id :github}]}
```

Production deployments can still sandbox dangerous capabilities at the process
or infrastructure layer. That boundary should be outside the graph IR.

### 5. Typed signatures, inspired by DSPy

DSPy signatures show why prompts should not be the only contract. Agents and
graphs should eventually declare input/output shapes:

```clojure
{:kind :agent
 :id "researcher"
 :instructions "Research the question and cite sources."
 :signature {:inputs  {:question :string}
             :outputs {:answer :string
                       :citations [:vector :string]}}}
```

The immediate value is reliable self-editing. A controller can inspect a resource
contract before patching or calling it. Longer term, signatures give optimizers
and runners a stable target.

### 6. Dynamic graph construction

Graphs should be temporary living plans. The controller can build a graph for the
current situation, run it, patch it, or throw it away:

```clojure
{:op :put
 :resource {:kind :graph
            :id "next-attempt"
            :workflow {:karcarthy/type :parallel
                       :branches [{:karcarthy/type :agent-ref :name "critic"}
                                  {:karcarthy/type :agent-ref :name "implementer"}]}}}
```

The graph vocabulary should stay small:

- call;
- parallel/fanout;
- branch;
- loop;
- return/complete.

### 7. Evaluation without freezing the runtime

Metrics and examples should be first-class runtime resources, but optimization
should not require freezing the agent. Useful eval flows:

- the agent runs itself against examples;
- the agent patches itself based on failures;
- the runtime records before/after state and scores;
- later runs can replay or fork any attempt.

The output is not necessarily a promoted static artifact. It can simply be the
next evolved state.

### 8. Observability

OpenTelemetry instrumentation is already present. The next layer is a stable
event model:

- operation events;
- resource mutation events;
- model call events;
- graph call events;
- tool call events;
- integration discovery events;
- snapshot/replay/fork events;
- cost, latency, token, and turn counters.

The trace viewer should make it obvious why the agent changed itself.

### 9. Distribution and compatibility

Before calling this production-ready:

- publish releases instead of requiring git SHAs;
- document compatibility guarantees for runtime state versions;
- add a CLI linter: `karcarthy validate state.edn`;
- add a small server mode for language-neutral callers;
- include real-runner examples for Claude, OpenAI, and a local command runner;
- keep offline tests as the default gate and add opt-in live conformance tests.

## Non-goals

- Reimplementing every provider's agent loop.
- Owning vector stores or UI streaming directly.
- Turning dynamic self-evolution into a static PR-promotion workflow.
- Putting approval requests in the core graph IR.
- Treating prompt strings as the stable production API.

The useful production shape is a small, durable, inspectable runtime where the
agent can continuously rewrite its own execution state as data.

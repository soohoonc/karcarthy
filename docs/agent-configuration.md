# Runtime vocabulary

There should not be a separate "dynamic mode" in karcarthy. The default runtime
model is that execution state is data, and an agent can change that data while it
runs. The current `karcarthy.dynamic` namespace is an implementation staging
area, not a separate abstraction.

```text
runtime -> agents + workflows + history -> next operation -> evolved runtime
```

The runtime should stay small. Claude, Codex, OpenAI Agents SDK, local models,
and command adapters still own the inner model/tool loop. karcarthy only owns
the data substrate around that loop.

## State

A running system needs only three core registries right now:

```clojure
{:agents {"planner" {:karcarthy/type :agent
                     :name "planner"
                     :instructions "..."}}
 :workflows {"main" {:karcarthy/type :chain
                     :steps [{:karcarthy/type :agent-ref
                              :name "planner"}]}}
 :history [{:operation {...}
            :result {...}}]}
```

Everything else should stay where it already belongs until there is a concrete
need to promote it:

- tools live on agents, Agent SDKs, CLIs, or their adapters;
- integrations live in SDK/CLI options or MCP config;
- context is input/history passed to the current call;
- safety boundaries live in the host runtime, not in the graph vocabulary.

## Operations

Keep the operation surface small.

### `:put`

Create or replace an agent or workflow.

```clojure
{:op :put
 :resource {:kind :agent
            :id "planner"
            :instructions "Plan the next operation."}}

{:op :put
 :resource {:kind :workflow
            :id "main"
            :workflow {:karcarthy/type :chain
                       :steps [{:karcarthy/type :agent-ref
                                :name "planner"}]}}}
```

### `:patch`

Merge changes into an existing agent or workflow.

```clojure
{:op :patch
 :kind :agent
 :id "planner"
 :merge {:instructions "Plan the next operation and explain tradeoffs."}}
```

### `:remove`

Remove an agent or workflow.

```clojure
{:op :remove
 :kind :agent
 :id "planner"}
```

### `:call`

Invoke an agent or workflow.

```clojure
{:op :call
 :target "planner"
 :input "What should happen next?"}
```

Homogeneous fanout is the same operation over many inputs:

```clojure
{:op :call
 :target "reviewer"
 :for-each ["api.clj" "db.clj" "ui.clj"]}
```

Heterogeneous fanout should use the existing workflow node:

```clojure
{:op :put
 :resource {:kind :workflow
            :id "review-swarm"
            :workflow {:karcarthy/type :parallel
                       :branches [{:karcarthy/type :agent-ref :name "api-reviewer"}
                                  {:karcarthy/type :agent-ref :name "db-reviewer"}
                                  {:karcarthy/type :agent-ref :name "ux-reviewer"}]}}}
```

### `:complete`

End the current top-level run.

```clojure
{:op :complete
 :text "Created a worker, patched it after observing output, and ran the patched version."}
```

## Production Requirements

Production readiness means the default runtime can survive and explain itself:

- append-only operation log;
- state snapshots;
- replay from the log;
- fork from a prior operation;
- crash recovery;
- trace viewer over operations and calls;
- resource accounting for model calls, latency, tokens, and cost;
- schema normalization so the agent can edit state reliably.

Do not add a new resource kind until the runtime needs to interpret it. The
smallest useful surface is agents, workflows, calls, and history.

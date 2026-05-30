# Self-evolving runtime vocabulary

karcarthy's production direction is a living runtime, not a static agent
configuration system. The deployed artifact is the interpreter/kernel. The agent
state inside that kernel is data, and the controller can rewrite that data while
it runs.

```text
runtime kernel -> agent state -> operation log -> evolved state -> next action
```

The runtime should stay small. Claude, Codex, OpenAI Agents SDK, local models, or
other runners still own the inner model/tool loop. karcarthy owns the mutable
data substrate around that loop: agents, graphs, integrations, tools,
environments, context, history, and operations.

## Design rules

- Keep execution state as plain data.
- Let the controller mutate that state directly.
- Treat history as an append-only event log, not a review queue.
- Treat recovery as snapshot/replay/fork, not approval.
- Treat integrations and tools as capabilities available in the agent's world.
- Prefer a small operation set: put, patch, remove, call, emit, return, complete.
- Keep schema normalization so the agent can edit reliably.

## Runtime State

A running system has a few living registries:

```clojure
{:agents {"planner" {...}
          "critic" {...}}
 :workflows {"next-attempt" {...}}
 :resources {"environment" {"default" {...}}
             "integration" {"github" {...}}
             "tool" {"github/list-issues" {...}}}
 :history [{:operation {...}
            :result {...}}]}
```

The exact storage can be in memory, SQLite, an event stream, or a durable
workflow engine. The model should see the state as EDN and respond with exactly
one operation.

## Resource Kinds

### `:agent`

An agent is a callable specialist. The controller can create, patch, remove, and
call agents at runtime.

```clojure
{:kind :agent
 :id "critic"
 :instructions "Find flaws in the current plan."
 :model "sonnet"
 :tools ["Read" "Grep"]}
```

### `:graph`

A graph is a callable execution plan. Graphs can be temporary. The controller can
compose a graph for the current situation, run it, discard it, or patch it based
on observations.

```clojure
{:kind :graph
 :id "next-attempt"
 :workflow {:karcarthy/type :chain
            :steps [{:karcarthy/type :agent-ref :name "planner"}
                    {:karcarthy/type :agent-ref :name "critic"}]}}
```

The current implementation accepts karcarthy workflow nodes as graph resources.
The target IR can grow richer graph operations like `:parallel`, `:branch`, and
`:loop`.

### `:environment`

An environment describes the world available to the agent. It replaces `:policy`
as the core concept. It is not a gate; it is the capability surface.

```clojure
{:kind :environment
 :id "default"
 :capabilities [:model :spawn-agent :patch-runtime :filesystem :network :mcp]
 :roots [{:type :filesystem :path "/repo"}
         {:type :runner :id :claude}
         {:type :mcp :id :github}]}
```

Production systems can still enforce process-level sandboxing outside the IR,
but the core vocabulary should describe what the agent can use, not ask for
permission as a graph operation.

### `:integration`

An integration is a connection to an external capability provider, usually MCP,
an API, a shell/runtime, or a hosted tool surface.

```clojure
{:kind :integration
 :id "github"
 :type :mcp
 :transport :http
 :url "https://api.githubcopilot.com/mcp/"
 :auth {:type :oauth}
 :exposes [:tools :resources]}
```

### `:tool`

A tool is one callable capability exposed directly or through an integration.

```clojure
{:kind :tool
 :id "github/list-issues"
 :from "github"
 :description "List issues for a repository."
 :input-schema {:type :object
                :required [:owner :repo]
                :properties {:owner {:type :string}
                             :repo {:type :string}}}}
```

Tools can be loaded eagerly, discovered from integrations, or loaded lazily
through a tool-search style surface.

## Context

`context` is the scoped data available to a single prompt, call, or graph run.
It is not memory, policy, or integrations.

Examples:

- user prompt/content blocks;
- attached files, images, URLs, and snippets;
- session history or compacted summary;
- graph variables from prior calls;
- artifacts and tool results;
- environment facts like `cwd`, repo, branch, and active runtime state.

```clojure
{:op :call
 :target "planner"
 :input "Choose the next action."
 :context {:resources [{:uri "file:///repo/README.md"
                        :mime-type "text/markdown"}]
           :summary "Prior decision: build a self-evolving runtime."
           :bindings {:goal "Improve the agent system."}}}
```

The UX should make context feel like attachments, selected resources, summaries,
and bindings. The agent can then decide what to preserve, discard, summarize, or
turn into new runtime resources.

## Operations

### `:put`

Create or replace a resource.

```clojure
{:op :put
 :resource {:kind :agent
            :id "planner"
            :instructions "Plan the next operation."}}
```

### `:patch`

Merge changes into a resource.

```clojure
{:op :patch
 :kind :agent
 :id "planner"
 :merge {:instructions "Plan the next operation and explain tradeoffs."}}
```

### `:remove`

Delete a resource from the living runtime.

```clojure
{:op :remove
 :kind :agent
 :id "planner"}
```

### `:call`

Invoke an agent or graph.

```clojure
{:op :call
 :target "planner"
 :input "What should happen next?"
 :as :plan}
```

Homogeneous fanout is a call over many inputs:

```clojure
{:op :call
 :target "reviewer"
 :for-each ["api.clj" "db.clj" "ui.clj"]
 :as :reviews}
```

Heterogeneous fanout should be represented as a graph with parallel branches:

```clojure
{:op :put
 :resource {:kind :graph
            :id "review-swarm"
            :workflow {:karcarthy/type :parallel
                       :branches [{:karcarthy/type :agent-ref :name "api-reviewer"}
                                  {:karcarthy/type :agent-ref :name "db-reviewer"}
                                  {:karcarthy/type :agent-ref :name "ux-reviewer"}]}}}
```

### `:emit`

Record or stream visible progress.

```clojure
{:op :emit
 :content "Created critic and implementer agents; running both now."}
```

### `:return`

Return a value from a graph/subgraph.

```clojure
{:op :return
 :value {:selected-plan :parallel-review}}
```

### `:complete`

End the current top-level dynamic run.

```clojure
{:op :complete
 :text "The runtime created a worker, patched it after observing output, and ran the patched version."}
```

## Current Implementation

`karcarthy.dynamic` now accepts the living-runtime operation names:

- `:put`
- `:patch`
- `:remove`
- `:prompt`
- `:call`
- `:emit`
- `:return`
- `:complete`

It also keeps the early names for compatibility:

| Legacy operation | Living-runtime operation |
| --- | --- |
| `:define-agent` | `:put` with `{:kind :agent ...}` |
| `:patch-agent` | `:patch` with `:kind :agent` |
| `:define-workflow` | `:put` with `{:kind :graph ...}` |
| `:patch-workflow` | `:patch` with `:kind :graph` |
| `:run-agent` | `:call` |
| `:run-workflow` | `:call` |
| `:run` | `:call` with an inline workflow |
| `:answer` | `:complete` |

The implementation still does not mutate host source code or add new Clojure
interpreter methods by itself. It mutates runtime data. To let the agent modify
source code, expose that as an environment capability and tool surface.

## Production Runtime Requirements

Production readiness in this model means durability and inspectability, not
human promotion gates.

- append-only operation log;
- periodic state snapshots;
- replay from genesis or from a snapshot;
- fork from any prior state;
- crash recovery;
- trace viewer over operations, model calls, tool calls, and graph calls;
- resource accounting for tokens, cost, latency, and external calls;
- capability discovery for tools, integrations, runners, and environments;
- schema normalization and migrations so the agent can reliably edit itself;
- opt-in process sandboxing around dangerous capabilities.

The goal is an agent OS: a small interpreter that lets the model continuously
rewrite the data that defines its own execution.

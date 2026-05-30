# Agent configuration vocabulary

karcarthy should expose a small data model that matches common agent protocol and
SDK concepts, while keeping the graph itself editable as data. The goal is not to
mirror every provider API. The goal is a compact IR that can compile to ACP,
Claude, OpenAI Agents SDK, Codex-style runners, or local command runners.

## Design rules

- Keep the execution plan as plain data.
- Separate resources from runtime operations.
- Treat context as scoped run/session data, not as a giant "memory" resource.
- Treat integrations as connection boundaries and tools as capabilities exposed
  by those boundaries.
- Keep permission prompts and approvals in policy/runtime adapters, not in the
  core graph IR.
- Prefer graph shapes that compose: call, parallel, branch, loop.

## Resource kinds

### `:agent`

An agent is a callable specialist.

```clojure
{:kind :agent
 :id :reviewer
 :description "Review code changes for correctness risks."
 :instructions "Prioritize bugs, regressions, and missing tests."
 :model {:provider :openai
         :name "gpt-5.5"}
 :tools [:repo/read :test/run]
 :policy :code-review}
```

Use a new agent only when the instructions, tools, output contract, or policy
are materially different. Otherwise keep the work inside one agent or one graph.

### `:graph`

A graph is a reusable execution plan. It can call agents, tools, or other graphs.

```clojure
{:kind :graph
 :id :review-then-summarize
 :steps [{:op :call :target :reviewer :as :findings}
         {:op :call :target :summarizer :with [:findings] :as :summary}
         {:op :return :value :summary}]}
```

The graph is karcarthy's local IR. ACP, Claude, and OpenAI do not expose this as
the same primitive; adapters compile it into sessions, agent calls, tool calls,
or SDK runs.

### `:integration`

An integration is a connection to an external capability provider, usually MCP,
an API, a shell/runtime, or a hosted tool surface.

```clojure
{:kind :integration
 :id :github
 :type :mcp
 :transport :http
 :url "https://api.githubcopilot.com/mcp/"
 :auth {:type :oauth}
 :exposes [:tools :resources]}
```

Integrations own connection, authentication, discovery, and trust boundaries.
Tools are the individual callable capabilities that come from integrations.

### `:tool`

A tool is one callable capability with a schema and side-effect metadata.

```clojure
{:kind :tool
 :id :github/list-issues
 :from :github
 :description "List issues for a repository."
 :input-schema {:type :object
                :required [:owner :repo]
                :properties {:owner {:type :string}
                             :repo {:type :string}}}
 :effects #{:read}}
```

Tools may be declared directly, discovered from an integration, or deferred until
needed through a tool-search style mechanism.

### `:policy`

A policy constrains execution. It is not a prompt and not a graph step.

```clojure
{:kind :policy
 :id :code-review
 :limits {:max-tool-calls 50
          :timeout-ms 300000}
 :side-effects {:filesystem-read :allow
                :filesystem-write :deny
                :shell :deny
                :network :allow}
 :redaction {:secrets true}}
```

If an ACP adapter needs to ask a client for permission, it can translate policy
decisions into ACP methods. The core graph should not contain a
`:request-permission` operation.

## Runtime context

`context` means the scoped data available to a prompt, call, or graph run. It is
not the same thing as memory, policy, integrations, or tools.

Examples:

- user prompt/content blocks;
- attached files, images, URLs, and snippets;
- session history or compacted summary;
- graph variables from prior steps;
- artifacts and tool results;
- environment facts such as `cwd`, repo, branch, and active config.

```clojure
{:op :prompt
 :target :planner
 :input "Design the migration."
 :context {:resources [{:uri "file:///repo/README.md"
                        :mime-type "text/markdown"}]
           :summary "Prior decision: use ACP-style sessions."
           :bindings {:target-system :karcarthy}}}
```

The UX should expose context as attachments, selected resources, session state,
summaries, and named bindings. Users should not need to manage a vague "memory"
object for ordinary runs.

Named reusable context bundles can exist later, but they should be treated as
inputs to a run rather than the central abstraction.

## Operations

There are two categories of operations: configuration operations and execution
operations.

### Configuration operations

Use these to mutate the data registry.

```clojure
{:op :put
 :resource {:kind :agent
            :id :planner
            :instructions "Plan the next step."}}

{:op :patch
 :kind :agent
 :id :planner
 :merge {:instructions "Plan the next step and explain tradeoffs."}}

{:op :remove
 :kind :agent
 :id :planner}
```

`put` replaces or creates a resource. `patch` changes a resource. `remove`
deletes a resource.

### Execution operations

Use these inside graphs or at the session boundary.

```clojure
{:op :prompt
 :target :planner
 :input "What should happen next?"
 :as :plan}

{:op :call
 :target :reviewer
 :with [:plan]
 :as :review}

{:op :emit
 :content "Running review and tests."}

{:op :return
 :value :review}

{:op :complete
 :reason :end-turn}
```

`prompt` is the external user-turn boundary. `call` invokes an agent, graph, or
tool from inside a graph. `emit` sends visible progress or messages. `return`
returns a value from a graph. `complete` ends a prompt turn.

Do not use `:answer` as a primitive. It conflates visible text, graph return
values, and turn completion.

## Graph shapes

### `:call`

One target invocation.

```clojure
{:op :call
 :target :researcher
 :input {:question "What changed in ACP config options?"}
 :as :research}
```

### `:parallel`

Fan out over independent branches, then optionally synthesize.

```clojure
{:op :parallel
 :branches [{:op :call :target :api-reviewer :as :api}
            {:op :call :target :db-reviewer :as :db}
            {:op :call :target :ux-reviewer :as :ux}]
 :concurrency 3
 :then {:op :call
        :target :synthesizer
        :with [:api :db :ux]
        :as :summary}}
```

### `:call` with `:for-each`

Use this for homogeneous fanout: the same target over many items.

```clojure
{:op :call
 :target :reviewer
 :for-each :changed-files
 :as :file-reviews
 :concurrency 8}
```

### `:branch`

Choose one path based on data.

```clojure
{:op :branch
 :on [:plan :kind]
 :cases {:simple [{:op :call :target :implementer}]
         :risky  [{:op :call :target :architect}
                  {:op :call :target :implementer}]}
 :default [{:op :call :target :planner}]}
```

### `:loop`

Repeat until a condition or limit.

```clojure
{:op :loop
 :while {:not :tests-passing?}
 :max-iterations 5
 :body [{:op :call :target :fixer}
        {:op :call :target :test-runner :as :test-result}]}
```

## Mapping to current code

The current `karcarthy.dynamic` namespace is the first implementation experiment.
It proves that execution state can be data and can evolve while the controller is
running. It still uses early operation names:

| Current operation | Target operation |
| --- | --- |
| `:define-agent` | `:put` with `{:kind :agent ...}` |
| `:patch-agent` | `:patch` with `:kind :agent` |
| `:define-workflow` | `:put` with `{:kind :graph ...}` |
| `:patch-workflow` | `:patch` with `:kind :graph` |
| `:run-agent` | `:call` |
| `:run-workflow` | `:call` |
| `:run` | `:prompt` at the boundary or `:call` inside a graph |
| `:answer` | `:emit`, `:return`, or `:complete` depending on intent |

The next implementation pass should normalize these names and add schema
validation before broadening behavior.

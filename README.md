# karcarthy

> A homoiconic Clojure harness in which agents can write, check, evaluate, and
> run new agents.

[![test](https://github.com/soohoonc/karcarthy/actions/workflows/test.yml/badge.svg)](https://github.com/soohoonc/karcarthy/actions/workflows/test.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

karcarthy is an agent harness, not a workflow DSL and not a portability layer
over other harnesses. It owns the model/tool loop. Agents, tools, and
orchestration are executable Clojure values and forms.

```clojure
(require '[karcarthy :as k])

(k/defagent researcher
  {:model {:transport :responses :id "gpt-5.6"}
   :instructions "Research carefully and cite evidence."
   :tools [web-search]
   :output ::report})

(k/defagent research-team
  {:input ::request
   :output ::report}
  [rt request]
  (let [draft (k/invoke! rt researcher request)
        checks (k/await-all!
                [(k/spawn! rt fact-checker draft)
                 (k/spawn! rt citation-checker draft)])]
    (k/invoke! rt editor {:draft draft :checks checks})))
```

There is no `pipe`, `branch`, or separate `dynamic` subsystem. Sequencing is
`let`, routing is `if` or `case`, parallelism is structured child execution,
and dynamic behavior is normal Lisp evaluation. Give a model the capability
explicitly:

```clojure
(k/defagent architect
  {:model {:transport :responses :id "gpt-5.6"}
   :instructions "Design and run the smallest useful child Agent."
   :tools [(k/agent)]})
```

`agent` is recursive at the interface: with a config it constructs an Agent;
with no arguments it is the model-facing `agent` Tool. Its input is one
`(agent ...)` form and a value. Underneath, `compile-agent!` reads the source,
macroexpands it, checks compiler and boundary contracts, evaluates it, verifies
that it produced an Agent, invokes it, and records every phase in the same run
trace. The child may recursively expand into more agents within shared limits.

## Why Clojure

The agent program and its representation are the same thing. An Agent retains
its source and expanded forms, so programs can be inspected, transformed,
generated, evaluated, diffed, and studied with ordinary Clojure.

This is useful for engineering and research:

- applications get typed tools, dynamic instructions, local context, optional
  Sessions, streaming, approvals, limits, cancellation,
  and observable child agents;
- experiments get exact program forms, model/Tool events, token usage, latency,
  lineage, and failures;
- optimizers can search over actual Clojure programs rather than a closed
  workflow grammar;
- Harbor will evaluate the harness through an ACP server without becoming the
  runtime underneath it.

## The small kernel

- `agent` / `defagent`
- `tool` / `deftool`
- `run!`
- `invoke!`
- `compile-agent!`
- `emit!`

Conveniences such as `spawn!`, `await-all!`, and `as-tool` build on those
primitives. A model provider is narrow model I/O; it does not own tools or
orchestration and is not a Runner.

## Minimal local tools

Following Pi's small-kernel design, karcarthy provides five orthogonal local
tools—`read`, `write`, `edit`, `bash`, and `search`. The readable base prompt
lives in [`resources/karcarthy/system.md`](resources/karcarthy/system.md).
`prompt`, `prompt-file`, and `system-prompt` compose ordinary instructions;
there is no directory-specific prompt builder or special coding Agent.

```clojure
(def tools
  (conj (k/local-tools {:cwd "/workspace/project"})
        (k/responses-web-search)))

(def coder
  (k/agent
   {:name "coder"
    :model {:transport :responses :id "gpt-5.6"}
    :instructions (k/prompt
                   (k/system-prompt)
                   "Follow the repository instructions supplied by the caller.")
    :tools tools
    :output string?}))

(k/run! coder "Fix the failing test and verify it.")
```

MCP tools enter through the same `:tools` vector. The inner loop does not know
or care whether a function tool was authored locally or discovered from an MCP
server. The ACP server exposes an Agent or per-session Agent factory to editors
and evaluation clients such as Harbor.

Agent `:instructions` is either a string or a function of the Runtime view.
Local handles, credentials, and user metadata are passed separately as
`:context` and are never shown to the model automatically. Runs are stateless
unless the caller supplies a `Session`:

```clojure
(def session (k/memory-session))
(k/run! coder "Inspect the failure." {:session session})
(k/run! coder "Now fix it." {:session session})
```

`memory-session` is process-local. Applications that need durability implement
the small `karcarthy.session/Session` protocol with their own database or
storage layer.

Streaming transports emit normalized text and tool-call deltas. `run!` exposes
them through `:observe`, and the ACP server forwards text deltas immediately as
`agent_message_chunk` updates.

`:transport :responses` selects the built-in Responses-compatible transport. It
defaults to OpenAI, while `:base-url`, `:api-key-env`, and the unchanged model
ID can target a compatible gateway without adding a provider implementation:

```clojure
{:transport :responses
 :provider :anthropic
 :id "anthropic/claude-model"
 :base-url "https://ai-gateway.vercel.sh/v1"
 :api-key-env "AI_GATEWAY_API_KEY"}
```

## Status

The native kernel, instructions/context split, explicit Sessions, streaming
model/tool loop, local tools, structured child execution,
generated-form evaluation, Responses-compatible HTTP/SSE transport, hosted
web search, stdio MCP client, and ACP v1 server are implemented. The former
Runner/EDN workflow implementation and JSON workflow bridge have been removed.

The documentation site describes the implemented programming model:

- [`docs/content/docs/index.mdx`](docs/content/docs/index.mdx) — premise
- [`docs/content/docs/quickstart.mdx`](docs/content/docs/quickstart.mdx) — API
- [`docs/content/docs/workflows.mdx`](docs/content/docs/workflows.mdx) — programs as Clojure
- [`docs/content/docs/runners.mdx`](docs/content/docs/runners.mdx) — the native harness
- [`docs/content/docs/reference/`](docs/content/docs/reference/) — API, generated code, and contracts

Remaining work, including crash-safe suspension and exactly-once effects, lives in
[`ROADMAP.md`](ROADMAP.md).

## Development

```bash
clojure -M:test
clojure -M -m karcarthy.demo
KARCARTHY_LIVE=1 OPENAI_API_KEY=... clojure -M:live-test
clojure -M -m karcarthy.acp your.namespace/agent-var
cd docs && npm ci && npm run build
```

JDK 21+. `local-tools` search also requires ripgrep. MIT licensed.

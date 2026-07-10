# karcarthy

> A homoiconic Clojure agent harness where models can create and run new Agents
> through a Tool call.

[![test](https://github.com/soohoonc/karcarthy/actions/workflows/test.yml/badge.svg)](https://github.com/soohoonc/karcarthy/actions/workflows/test.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

In karcarthy, an Agent is both executable Clojure and inspectable Clojure data.
The same form can be authored by a developer, emitted by a model, macroexpanded,
transformed, evaluated, and run.

That homoiconic representation powers the central capability: add the built-in
`agent` Tool to let a model submit a new Agent definition that karcarthy checks,
evaluates, and runs as a child of the current Run.

The harness also provides a model/Tool loop, typed Tools, conversation Sessions,
streaming events, structured child Agents, MCP, and ACP.

## Install

JDK 21 and Clojure CLI are required.

```clojure
;; deps.edn
{:deps
 {io.github.soohoonc/karcarthy
  {:git/url "https://github.com/soohoonc/karcarthy"
   :git/sha "<commit-sha>"}}}
 :paths ["src"]}
```

## Define and run an Agent

```clojure
(require '[karcarthy :as k])

(k/defagent researcher
  {:model {:transport :responses :id "gpt-5.6"}
   :instructions "Answer accurately. Search the web for current facts."
   :tools [(k/responses-web-search)]
   :output string?})

(def run
  (k/run! researcher "What changed in the latest Java release?"))

(:status run) ;=> :completed
(:output run) ;=> "..."
```

The Responses transport reads `OPENAI_API_KEY` by default. `run!` returns
status, typed output, usage, events, and structured failure data.

## Let a model create an Agent

Zero-arity `(k/agent)` returns a Tool named `agent`:

```clojure
(k/defagent architect
  {:model {:transport :responses :id "gpt-5.6"}
   :instructions
   "Solve the task. Create a focused child Agent when specialization helps."
   :tools [(k/agent)]
   :output string?})

(k/run! architect task
        {:limits {:agent-depth 4
                  :generated-forms 8
                  :model-calls 40}})
```

The model calls the Tool with:

```json
{
  "source": "(agent {:name \"specialist\" ...})",
  "input": {"task": "..."}
}
```

karcarthy reads one Clojure form, macroexpands and checks it, evaluates it,
verifies that it produced an Agent, and invokes the child. Generated Agents may
receive the same Tool and create more Agents within shared limits.

## `agent` and `defagent`

```clojure
;; Construct an Agent value.
(def reviewer
  (k/agent {:name "reviewer"
            :model {:transport :responses :id "gpt-5.6"}
            :instructions "Review the change."
            :output string?}))

;; Define a var containing the same kind of Agent.
(k/defagent reviewer
  {:model {:transport :responses :id "gpt-5.6"}
   :instructions "Review the change."
   :output string?})
```

`defagent` supplies the default Agent name from the var name. It does not
create a separate Agent type.

Agents can also have a Clojure body for deterministic coordination:

```clojure
(k/defagent review-team
  {:input map? :output string?}
  [rt change]
  (let [reviews (k/await-all!
                 [(k/spawn! rt security-reviewer change)
                  (k/spawn! rt api-reviewer change)])]
    (k/invoke! rt editor {:change change :reviews reviews})))
```

## Local and remote Tools

```clojure
(def tools
  (conj (k/local-tools {:cwd "/workspace/project"})
        (k/responses-web-search)))
```

`local-tools` returns `read`, `write`, `edit`, `bash`, and ripgrep-backed
`search`. MCP definitions are adapted into the same Tool values with
`connect-mcp!` and `mcp-tools`.

ACP v1 serving exposes a static Agent or per-session Agent factory to editors
and evaluation systems such as Harbor:

```bash
clojure -M -m karcarthy.acp your.namespace/agent-var
```

## Documentation

- [Quickstart](docs/content/docs/quickstart.mdx)
- [Agents](docs/content/docs/agents.mdx)
- [Tools](docs/content/docs/tools.mdx)
- [Running agents](docs/content/docs/running-agents.mdx)
- [Sessions](docs/content/docs/sessions.mdx)
- [Streaming and events](docs/content/docs/streaming.mdx)
- [Integrations](docs/content/docs/integrations.mdx)
- [Examples](docs/content/docs/examples/index.mdx)
- [Reference](docs/content/docs/reference/index.mdx)

## Development

```bash
clojure -M:test
clojure -M -m karcarthy.demo
KARCARTHY_LIVE=1 OPENAI_API_KEY=... clojure -M:live-test
clojure -T:build all
cd docs && npm ci && npm run lint && npm run types:check && npm run build
```

The local `search` Tool additionally requires ripgrep. karcarthy is MIT
licensed.

# karcarthy

karcarthy is a small Clojure agent harness inspired by Lisp's homoiconicity.
Clojure itself is the workflow engine: Agents are values and forms, and normal
functions, macros, control flow, and concurrency compose them.

[![test](https://github.com/soohoonc/karcarthy/actions/workflows/test.yml/badge.svg)](https://github.com/soohoonc/karcarthy/actions/workflows/test.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

## Run the offline example

JDK 21 and the Clojure CLI are required.

```bash
clojure -M:examples hello "hello"
```

This calls a Clojure Tool through a deterministic fake model and prints
`HELLO`. It does not require a network call.

## Define and run an Agent

```clojure
(require '[karcarthy :as k])

(k/defagent assistant
  {:model {:transport :responses :id "gpt-5.6"}
   :instructions "Answer clearly and concisely."
   :output string?})

(def run
  (k/run! assistant "Explain continuation-passing style."))

(:status run) ;=> :completed
(:output run) ;=> "..."
```

The built-in Responses transport reads `RESPONSES_API_KEY` or
`OPENAI_API_KEY`.

## Add a Tool

```clojure
(k/deftool word-count
  {:description "Count the words in text."
   :input {:type "object"
           :properties {"text" {:type "string"}}
           :required ["text"]
           :additionalProperties false}
   :output integer?}
  [{:keys [text]}]
  (count (re-seq #"\S+" text)))
```

Put the Tool in an Agent with `:tools [word-count]`. The model receives its
name, description, and schema; karcarthy validates and executes its calls.

## Make another Agent available

```clojure
(k/defagent editor
  {:model {:transport :responses :id "gpt-5.6"}
   :instructions "Write the answer. Ask researcher when useful."
   :agents [researcher]
   :output string?})
```

The editor's model can call `researcher`. Its output returns to the
editor, which continues to the final answer.

## Let a model write Clojure

```clojure
(k/defagent architect
  {:model {:transport :responses :id "gpt-5.6"}
   :instructions "Write and run a focused Agent when useful."
   :output string?})
```

Every model Agent may submit a new `(agent ...)` form. karcarthy reads, expands,
evaluates, verifies, and runs the resulting Agent. A generated form can use
ordinary Clojure and call other Agents. Authored and generated orchestration
therefore share one language and representation.

The built-in `agent` Tool explains the exact grammar, when generation is
useful, when it is unnecessary, and the model, Tool, and Agent symbols actually
available. Its call contains the Clojure `source` and an explicit `input`.
Generated Agents do not inherit the parent's model conversation.

This executes model-authored JVM Clojure and is not a sandbox.

## Documentation

- [Home](docs/content/docs/index.mdx)
- [Quickstart](docs/content/docs/quickstart.mdx)
- [REPL development](docs/content/docs/repl.mdx)
- [Agents](docs/content/docs/agents.mdx)
- [Tools](docs/content/docs/tools.mdx)
- [MCP](docs/content/docs/mcp.mdx)
- [ACP](docs/content/docs/acp.mdx)
- [Harbor](docs/content/docs/harbor.mdx)
- [Examples](docs/content/docs/examples/index.mdx)
- [Reference](docs/content/docs/reference/index.mdx)

## Development

```bash
clojure -M:test
clojure -M:examples hello
KARCARTHY_LIVE=1 OPENAI_API_KEY=... clojure -M:live-test
clojure -T:build all
cd docs && npm ci && npm run lint && npm run types:check && npm run build
```

karcarthy is MIT licensed.

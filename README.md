# karcarthy

karcarthy is a small Clojure agent harness. It runs model-backed Agents,
executes their Tools, and returns a structured Run containing output, usage,
events, and failures.

[![test](https://github.com/soohoonc/karcarthy/actions/workflows/test.yml/badge.svg)](https://github.com/soohoonc/karcarthy/actions/workflows/test.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

## Install

JDK 21 and the Clojure CLI are required.

```clojure
{:deps
 {io.github.soohoonc/karcarthy
  {:git/url "https://github.com/soohoonc/karcarthy"
   :git/sha "<commit-sha>"}}
 :paths ["src"]}
```

The built-in Responses transport reads `RESPONSES_API_KEY` or
`OPENAI_API_KEY`.

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

A model-backed Agent normally receives a prompt string. Local dependencies and
request data belong in the Run's `:context`; they are not sent to the model
automatically.

## Add a Tool

Tool inputs are structured because the model must know which arguments to
produce:

```clojure
(k/deftool word-count
  {:description "Count the words in text."
   :input {:type "object"
           :properties {:text {:type "string"}}
           :required ["text"]
           :additionalProperties false}
   :output integer?}
  [{:keys [text]}]
  (count (re-seq #"\S+" text)))
```

Add the Tool to an Agent with `:tools [word-count]`. Tool input and output are
validated around every call.

## Use Clojure for composition

An Agent may have a Clojure body. Its one input value is passed through
unchanged:

```clojure
(k/defagent select-request-fields
  {:input map? :output map?}
  [request]
  (select-keys request [:task :repository]))
```

Calls to `run!` start independent Runs. Use ordinary Clojure functions,
conditionals, `future`, and `deref` to coordinate them.

## Let a model create an Agent

Zero-arity `(k/agent)` returns a Tool named `agent`:

```clojure
(k/defagent architect
  {:model {:transport :responses :id "gpt-5.6"}
   :instructions
   "Complete the task. Create a focused Agent when specialization helps."
   :tools [(k/agent)]
   :output string?})
```

The model supplies a Clojure Agent form and an input. karcarthy reads,
macroexpands, checks, evaluates, verifies, and runs the Agent inside the current
Run. Authored and generated Agents use the same representation; this is the
homoiconic motivation for the library.

## More

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

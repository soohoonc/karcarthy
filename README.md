# karcarthy

> **The agent architecture is the program.**

karcarthy is a toy agent harness built around Clojure forms and macros. During
a Run, an Agent can submit another `(agent ...)` form; karcarthy expands it,
evaluates it, and runs the new Agent.

There is no workflow graph or second orchestration language. Clojure functions,
conditionals, recursion, and concurrency express fixed architectures. When the
right architecture depends on the task, a running Agent can write and run a new
`(agent ...)` form.

[![test](https://github.com/soohoonc/karcarthy/actions/workflows/test.yml/badge.svg)](https://github.com/soohoonc/karcarthy/actions/workflows/test.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

[Documentation](https://karcarthy.vercel.app/docs)

## Watch an Agent write more Agents

JDK 21, the Clojure CLI, and `RESPONSES_API_KEY` or `OPENAI_API_KEY` are
required:

```bash
clojure -M:examples architect \
  "Review a migration from synchronous writes to a queue."
```

The terminal redraws the live Agent tree as the Run changes:

```text
Run run_7c2e9b… · running · 18s · 3 model calls · 8,421 tokens · 2 Agent forms
└─ architect · waiting for 2 Agents
   ├─ failure-analyst · calling model
   └─ rollout-planner · calling model
```

Elapsed time updates once per second. Model-call and token totals update as
usage arrives. When both children finish, the tree marks them done and the
parent writes the answer.

## Agents and Clojure

Define an Agent with a model and instructions:

```clojure
(require '[karcarthy :as k])

(k/defagent assistant
  {:model {:transport :responses :id "gpt-5.6"}
   :instructions "Answer clearly and concisely."
   :output string?})

(def run (k/run! assistant "Explain continuation-passing style."))
(:output run) ;=> "..."
```

Or use ordinary Clojure to define a fixed architecture:

```clojure
(defn review-team [change]
  (->> [security-reviewer api-reviewer]
       (mapv #(future (k/run! % change)))
       (mapv deref)
       (mapv :output)))
```

Every Agent receives a built-in `agent` Tool. It accepts the source for exactly
one `(agent ...)` form and an explicit input. The form is ordinary Clojure,
evaluated while the Run is in progress.

## Watch live Runs at the REPL

`monitor` turns the event stream into a live Agent tree:

```clojure
(def live (k/monitor {:display :tree}))
(def run (k/run! assistant "Complete the task." {:observe live}))
```

Evaluate `(k/monitor live)` to see the current tree again. Use
`(k/monitor-state live)` only when you need the underlying Clojure data. A
monitor can observe several concurrent Runs.

## Examples

The examples progress from the kernel to a complete evaluation:

| Example | What it shows |
| --- | --- |
| [Basic](examples/basic/main.clj) | One Agent Run |
| [Architect](examples/architect/main.clj) | A running Agent authors and calls a task-specific team |
| [Compose agents](examples/composition/main.clj) | A fixed concurrent architecture written with Clojure |
| [Build a coding agent](examples/coding/main.clj) | Open-ended repository work with task-dependent architecture |
| [Evaluate an agent](examples/harbor/README.md) | The Coding Agent evaluated by a verifier with a recorded trajectory |

See [examples/README.md](examples/README.md) for commands and details.

## Boundaries

The harness owns the bounded model/Tool loop. Clojure owns control flow.
Sessions own conversation history. Model transports only translate I/O.

Runtime Agent source is evaluated as full-trust JVM Clojure. karcarthy validates
that it is one Agent form, but it is not a sandbox for untrusted code.

## Documentation

- [Quickstart](https://karcarthy.vercel.app/docs/quickstart)
- [Why Clojure?](https://karcarthy.vercel.app/docs/why-clojure)
- [Create agents at runtime](https://karcarthy.vercel.app/docs/guides/architect)
- [Agents and runs](https://karcarthy.vercel.app/docs/agents)
- [Tools and context](https://karcarthy.vercel.app/docs/tools)
- [API](https://karcarthy.vercel.app/docs/reference/api)

## Development

```bash
clojure -M:test
OPENAI_API_KEY=... clojure -M:examples basic "Say hello."
KARCARTHY_LIVE=1 OPENAI_API_KEY=... clojure -M:live-test
clojure -T:build all
cd docs && npm ci && npm run lint && npm run types:check && npm run build
```

karcarthy is MIT licensed.

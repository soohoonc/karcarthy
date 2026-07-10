# karcarthy

> **The agent architecture is the program.**

karcarthy is a native, homoiconic Clojure agent harness. Agents are executable
Clojure values and forms: developers, macros, and models all author the same
program, and the same kernel runs it.

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

The terminal trace makes the runtime path visible:

```text
RUN    architect
MODEL  -> new Agent

(agent
  {:name "failure-analyst"
   ...})

KERNEL read
KERNEL expand
KERNEL check
KERNEL evaluate -> failure-analyst
MODEL  -> new Agent

(agent
  {:name "rollout-planner"
   ...})

KERNEL read
KERNEL expand
KERNEL check
KERNEL evaluate -> rollout-planner
  RUN    failure-analyst
  RUN    rollout-planner
  DONE   failure-analyst
RETURN <- generated Agent
  DONE   rollout-planner
RETURN <- generated Agent
DONE   architect
```

The submitted source is not serialized workflow configuration. It is an
ordinary Agent program. karcarthy reads, macroexpands, checks, evaluates, and
runs it; each child receives only its explicit input, and both outputs return
to the parent Agent.

## One representation

A developer can define a model-driven Agent:

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
(k/defagent review-team
  {:input string? :output vector?}
  [change]
  (->> [security-reviewer api-reviewer]
       (mapv #(future (k/run! % change)))
       (mapv deref)
       (mapv :output)))
```

Every model-driven Agent also receives a built-in `agent` Tool. It accepts the
source for exactly one `(agent ...)` form and an explicit input. Authored and
runtime-generated architectures therefore share the same language, contracts,
Run limits, events, and nested lineage.

This is why karcarthy uses Clojure: an Agent's source is simultaneously an
executable program and data that a developer, macro, or model can produce.

## Examples

The examples progress from the kernel to a complete evaluation:

| Example | What it shows |
| --- | --- |
| [Basic](examples/basic/main.clj) | One model-driven Agent Run |
| [Architect](examples/architect/main.clj) | A running Agent authors and calls a task-specific team |
| [Composition](examples/composition/main.clj) | A fixed concurrent architecture written with Clojure |
| [Coding](examples/coding/main.clj) | Open-ended repository work with task-dependent architecture |
| [Harbor](examples/harbor/README.md) | The Coding Agent evaluated by a verifier with a recorded trajectory |

See [examples/README.md](examples/README.md) for commands and details.

## Boundaries

The harness owns the bounded model/Tool loop. Clojure owns control flow.
Sessions own conversation history. Model transports only translate I/O.

Runtime Agent source is evaluated as full-trust JVM Clojure. karcarthy validates
that it is one Agent form, but it is not a sandbox for untrusted code.

## Documentation

- [Quickstart](https://karcarthy.vercel.app/docs/quickstart)
- [Agents](https://karcarthy.vercel.app/docs/agents)
- [Runtime Agent forms](https://karcarthy.vercel.app/docs/reference/agent-forms)
- [Guides](https://karcarthy.vercel.app/docs/guides)
- [Protocols](https://karcarthy.vercel.app/docs/protocols/mcp)
- [Reference](https://karcarthy.vercel.app/docs/reference)

## Development

```bash
clojure -M:test
OPENAI_API_KEY=... clojure -M:examples basic "Say hello."
KARCARTHY_LIVE=1 OPENAI_API_KEY=... clojure -M:live-test
clojure -T:build all
cd docs && npm ci && npm run lint && npm run types:check && npm run build
```

karcarthy is MIT licensed.

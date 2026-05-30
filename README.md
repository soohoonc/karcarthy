# karcarthy

> Homoiconic agent orchestration for Clojure.

[![test](https://github.com/soohoonc/karcarthy/actions/workflows/test.yml/badge.svg)](https://github.com/soohoonc/karcarthy/actions/workflows/test.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

karcarthy coordinates many AI agents. The agents, tools, and the workflow itself
are plain Clojure data (EDN).

## Why Lisp, and why a runner

Running a single agent (the model-call and tool-call loop) is close to a
commodity now: the `claude` CLI, the OpenAI Agents SDK, and local models all do
it. The harder, more interesting problem is coordinating *many* agents while
keeping the plan something you can see and change.

In a Lisp, code is data, so karcarthy makes the plan a value. A workflow is an
EDN structure you generate, transform with `clojure.walk`, store, and diff like
any other data. karcarthy delegates the inner loop to a *runner* and keeps only
the data-first coordination layer on top. Two things fall out of that:

- you swap runners, or pick one per agent, without touching the workflow; and
- because the plan is data in a Lisp, an agent can **write a workflow as EDN
  that karcarthy runs, or rewrite its own definition at runtime**. The language
  the agents are described in is available to the agents themselves.

## Quickstart

It's early, with no published release yet, so depend on it from git:

```clojure
;; deps.edn
io.github.soohoonc/karcarthy {:git/url "https://github.com/soohoonc/karcarthy"
                              :git/sha "<commit sha>"}
```

```clojure
(require '[karcarthy :as k])   ; one alias re-exports the common API

(k/defagent researcher "Research the question and cite sources.")
(k/defagent summarizer "Summarize the findings in one sentence.")

;; a workflow is data; run it on any runner (mock is offline and deterministic)
(k/run (k/mock-runner) (k/chain researcher summarizer) "what is a monad?")
;=> {:karcarthy/type :result, :ok? true, :text "...", ...}
```

Swap `(k/mock-runner)` for `(k/claude-runner {})` to run it against `claude`.

## Highlights

- **Runners** behind one protocol: `mock`, `claude-cli` (streaming + sessions),
  `command` (wrap any CLI or local model), `openai`. Pass one, or pass a registry
  `{id -> runner}` and let each agent choose with `:runner`.
- **Workflow nodes**, all data: `chain`, `parallel`, `route`, `refine`,
  `orchestrate`, `handoff`, and multi-turn `converse`.
- **Popular orchestrator shapes as data**: `karcarthy.patterns` emulates
  LangGraph-style state graphs, CrewAI-style crews, AutoGen-style round-robin
  group chats, OpenAI-style handoff routing, and ADK-style workflow agents.
- **Agents speak karcarthy**: advanced self-modifying flows parse EDN via
  `clojure.edn`, never `eval`.
- **OpenTelemetry-ready**: wrap a runner with `karcarthy.otel/instrument` to
  emit spans for workflow nodes, embedded functions, and agent calls.

```clojure
(require '[karcarthy.otel :as otel])

(k/run (otel/instrument (k/mock-runner))
       (k/chain researcher summarizer)
       "what is a monad?")
```

## More

- Examples in Clojure, Java, Kotlin, Scala, Python, and TypeScript:
  [`examples/`](examples/). Non-JVM languages drive it over a JSON bridge
  (`karcarthy.cli`), since a workflow is just data.
- Runnable offline emulations of common orchestrator patterns:
  [`examples/clojure/orchestrator_emulations.clj`](examples/clojure/orchestrator_emulations.clj).
- How it compares to PydanticAI, DSPy, Agno, and the Vercel AI SDK:
  [`COMPARISON.md`](COMPARISON.md).
- What is missing for a production-ready default runtime:
  [`ROADMAP.md`](ROADMAP.md).
- Proposed runtime vocabulary and protocol references:
  [`docs/`](docs/).
- `clojure -M:test` runs the offline tests; `clojure -M -m karcarthy.demo` a demo.
- Pre-release (0.0.2). JDK 21+; depends on `org.clojure/clojure`,
  `org.clojure/data.json`, and the OpenTelemetry Java API.

## License

[MIT](LICENSE).

# karcarthy

> Homoiconic agent orchestration for Clojure.

[![test](https://github.com/soohoonc/karcarthy/actions/workflows/test.yml/badge.svg)](https://github.com/soohoonc/karcarthy/actions/workflows/test.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

karcarthy coordinates many AI agents. The agents, tools, and the workflow itself
are plain Clojure data (EDN).

## Why Lisp, and why a harness

Running a single agent (the model-call and tool-call loop) is close to a
commodity now: the `claude` CLI, the OpenAI Agents SDK, and local models all do
it. The harder, more interesting problem is coordinating *many* agents while
keeping the plan something you can see and change.

In a Lisp, code is data, so karcarthy makes the plan a value. A workflow is an
EDN structure you generate, transform with `clojure.walk`, store, and diff like
any other data. karcarthy delegates the inner loop to a *harness* and keeps only
the data-first coordination layer on top. Two things fall out of that:

- you swap harnesses, or pick one per agent, without touching the flow; and
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

;; a flow is data; run it on any harness (mock is offline and deterministic)
(k/run-flow (k/mock-harness) (k/chain researcher summarizer) "what is a monad?")
;=> {:karcarthy/type :result, :ok? true, :text "...", ...}
```

Swap `(k/mock-harness)` for `(k/claude-harness {})` to run it against `claude`.

## Highlights

- **Harnesses** behind one protocol: `mock`, `claude-cli` (streaming + sessions),
  `command` (wrap any CLI or local model), `openai`. Pass one, or pass a registry
  `{id -> harness}` and let each agent choose with `:harness`.
- **Flow nodes**, all data: `chain`, `parallel`, `route`, `refine`,
  `orchestrate`, `handoff`, and multi-turn `converse`.
- **Agents speak karcarthy**: `run-authored` (an agent writes a flow) and
  `evolve` (an agent edits its own definition), parsed as data via `clojure.edn`,
  never `eval`.

## More

- Examples in Clojure, Java, Kotlin, Scala, Python, and TypeScript:
  [`examples/`](examples/). Non-JVM languages drive it over a JSON bridge
  (`karcarthy.cli`), since a flow is just data.
- How it compares to PydanticAI, Agno, and the Vercel AI SDK: [`COMPARISON.md`](COMPARISON.md).
- `clojure -M:test` runs the offline tests; `clojure -M -m karcarthy.demo` a demo.
- Pre-release (0.0.2). JDK 21+; depends only on `org.clojure/clojure` and
  `org.clojure/data.json`.

## License

[MIT](LICENSE).

# karcarthy

> Homoiconic agent orchestration for Clojure.

[![test](https://github.com/soohoonc/karcarthy/actions/workflows/test.yml/badge.svg)](https://github.com/soohoonc/karcarthy/actions/workflows/test.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

karcarthy coordinates many AI agents. The agents, tools, and the workflow itself
are plain Clojure data (EDN).

## Why Lisp, and why Agent SDKs & CLIs

Running a single agent (the model-call and tool-call loop) is close to a
commodity now: the `claude` CLI, the OpenAI Agents SDK, and local models all do
it. The harder, more interesting problem is coordinating *many* agents while
keeping the plan something you can see and change.

In a Lisp, code is data, so karcarthy makes the plan a value. A workflow is an
EDN structure you generate, transform with `clojure.walk`, store, and diff like
any other data. karcarthy delegates the inner loop to systems people already
use: Pydantic AI, Claude Agent SDK/CLI, OpenAI Agents SDK, Clojure functions,
subprocesses, shell commands, or a local mock. karcarthy keeps only the
data-first coordination layer on top. Two things fall out of that:

- you swap Agent SDK/CLI runners, or pick one per agent, without touching the workflow; and
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

;; a workflow is data; run it through any runner (mock is offline and deterministic)
(k/run (k/mock-runner) (k/pipe researcher summarizer) "what is a monad?")
;=> {:karcarthy/type :result, :ok? true, :text "...", ...}
```

Swap the mock runner for `(k/claude-cli-runner {})` to run it against `claude`.

## Highlights

- **Runners** behind one protocol: `mock-runner`, `fn-runner`,
  `process-runner`, `claude-cli-runner`, and
  `openai-agents-runner`. Pass one runner, or pass a map and let each agent
  choose with `:runner`.
- **Workflows as data**: compose agents with `pipe`, `branch`, `delegate`,
  `reduce`, `revise`, `route`, `continue`, and `dynamic`; inspect and rewrite
  those workflow values before running them.
- **Host Clojure steps**: use `step` for local functions inside workflows; use
  `process-runner` for fixed argv or shell commands selected by the runner registry.
- **Runner-native subagents**: define `subagent` maps for Claude Code
  subagents or OpenAI Agents SDK handoffs while keeping workflow branches as
  explicit karcarthy orchestration.
- **Structural rewrites**: stamp configuration onto every agent without changing the
  original workflow:
  ```clojure
  (->> workflow
       (k/configure {:runner :claude
                     :model "claude-sonnet-4"
                     :instructions/suffix "State assumptions before final answer."}))
  ```
- **Agents speak karcarthy**: advanced workflows parse EDN via `clojure.edn`,
  never `eval`, so an agent can write a workflow before a run or adapt the
  workflow while the run is in progress.
- **Schemas as data**: `edn-schema` and `json-schema` keep generated workflows
  inspectable without adding another runtime.

## More

- Examples in Clojure, JavaScript, Java, Kotlin, Scala, Python, and TypeScript:
  [`examples/`](examples/). Non-JVM languages drive it through `bin/karcarthy`
  over JSON, since a workflow is just data.
- Fumadocs-powered web docs:
  [`docs/`](docs/) and
  [`karcarthy.vercel.app/docs`](https://karcarthy.vercel.app/docs).
- How it compares to PydanticAI, DSPy, Agno, and the Vercel AI SDK:
  [`COMPARISON.md`](COMPARISON.md).
- What is missing for a sharper core:
  [`ROADMAP.md`](ROADMAP.md).
- `clojure -M:test` runs the offline tests; `clojure -M -m karcarthy.demo` runs a demo.
- `clojure -T:build uber` builds `target/karcarthy-0.0.2-standalone.jar`;
  `./bin/karcarthy agent echo --instructions "Echo the input." hi` runs it as a CLI.
- Pre-release (0.0.2). JDK 21+; depends on `org.clojure/clojure` and
  `org.clojure/data.json`.

## License

[MIT](LICENSE).

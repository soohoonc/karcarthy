# karcarthy

> Homoiconic agent orchestration for Clojure.

[![test](https://github.com/soohoonc/karcarthy/actions/workflows/test.yml/badge.svg)](https://github.com/soohoonc/karcarthy/actions/workflows/test.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

karcarthy coordinates many AI agents from Clojure. You write agents, tools,
and the workflow itself as plain data (EDN), then run it through any runner.

## Get started

There is no published release yet, so depend on karcarthy from git:

```clojure
;; deps.edn
io.github.soohoonc/karcarthy {:git/url "https://github.com/soohoonc/karcarthy"
                              :git/sha "<commit sha>"}
```

Define two agents, compose them with `pipe`, and run the workflow:

```clojure
(require '[karcarthy :as k])   ; one alias re-exports the common API

(k/defagent researcher
  {:instructions "Research the question and cite sources."})

(k/defagent summarizer
  {:instructions "Summarize the findings in one sentence."})

;; the workflow is data; run it through any runner
(k/run {:runner (k/mock-runner)
        :workflow (k/pipe researcher summarizer)
        :input "what is a monad?"})
;=> {:karcarthy/type :result, :ok? true, :text "...", ...}
```

The mock runner is offline and deterministic. Swap it for
`(k/claude-runner {})`, `(k/codex-runner {:dir "."})`, `(k/openai-runner {})`,
or `(k/acp-runner {:command [...]})` to run the same workflow on a live agent
system.

## Highlights

- **Runners** behind one protocol: `mock-runner`, `fn-runner`,
  `process-runner`, `acp-runner`, `claude-runner`, `codex-runner`, and
  `openai-runner`. Pass the runner you want to `run`.
- **Workflows as data**: compose agents with `pipe`, `branch`, `delegate`,
  `reduce`, `revise`, `route`, `continue`, and the experimental `dynamic`;
  inspect and rewrite
  those workflow values before running them.
- **Host Clojure steps**: use `step` for local functions inside workflows; use
  `process-runner` when the whole run should execute agent calls through a
  fixed argv or shell command.
- **Runner-native subagents**: define `subagent` maps for Claude Code
  subagents, Codex custom-agent config, or OpenAI Agents SDK handoffs while
  keeping workflow branches as explicit karcarthy orchestration.
- **Structural rewrites**: stamp configuration onto every agent without changing the
  original workflow:
  ```clojure
  (->> workflow
       (k/configure {:model "claude-sonnet-4"
                     :instructions/suffix "State assumptions before final answer."}))
  ```
- **Agents speak karcarthy**: advanced workflows parse EDN via `clojure.edn`,
  never `eval`, so an agent can write a workflow before a run or adapt the
  workflow while the run is in progress.
- **Run options**: under `:options` in the run request, `:observe` streams
  OTel-compatible event maps for every workflow and agent span, and
  `:edn-retries` (default 1) lets planner, evaluator, router, and dynamic
  nodes self-repair by re-asking the model when an EDN reply fails to parse.
- **Schemas as data**: `edn-schema` and `json-schema` keep generated workflows
  inspectable without adding another runtime.

## Why Lisp, and why runners

Running a single agent (the model-call and tool-call loop) is close to a
commodity: Claude, Codex, the OpenAI Agents SDK, and local models all do it.
The harder problem is coordinating *many* agents while keeping the plan
something you can see and change.

In a Lisp, code is data, so karcarthy makes the plan a value. A workflow is an
EDN structure you generate, transform with `clojure.walk`, store, and diff like
any other data. karcarthy delegates the inner loop to systems you already use —
PydanticAI, Claude, Codex, the OpenAI Agents SDK, Clojure functions,
subprocesses, shell commands, or a local mock — and keeps only the data-first
coordination layer on top. Two things fall out of that:

- You swap provider, protocol, process, or mock runners without touching the
  workflow.
- Because the plan is data in a Lisp, an agent can **write a workflow as EDN
  that karcarthy runs, or rewrite its own definition at runtime**. The language
  the agents are described in is available to the agents themselves.

## Next steps

- Examples in Clojure, JavaScript, Java, Kotlin, Scala, Python, and TypeScript:
  [`examples/`](examples/). Non-JVM languages drive karcarthy through
  `bin/karcarthy` over JSON, since a workflow is just data.
- Web docs (Fumadocs): [`docs/`](docs/) and
  [`karcarthy.vercel.app/docs`](https://karcarthy.vercel.app/docs).
- How karcarthy compares to PydanticAI, DSPy, Agno, and the Vercel AI SDK:
  [`COMPARISON.md`](COMPARISON.md).
- What is missing for a sharper core: [`ROADMAP.md`](ROADMAP.md).
- `clojure -M:test` runs the offline tests; `clojure -M -m karcarthy.demo` runs a demo.
- `clojure -T:build uber` builds `target/karcarthy-0.0.2-standalone.jar`;
  `./bin/karcarthy agent echo --instructions "Echo the input." hi` runs it as a CLI.
- Pre-release (0.0.2). JDK 21+; depends on `org.clojure/clojure` and
  `org.clojure/data.json`.

## License

[MIT](LICENSE).

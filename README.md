# karcarthy

> **Agents write Clojure that runs Agents.**

karcarthy is a small, homoiconic agent library for Clojure. Its public ideas
are the familiar ones—Agents, Tools, Runs, Sessions, context, limits, and
events. Its distinguishing capability is a built-in `eval` Tool: while an
Agent is running, it can write one ordinary Clojure expression that creates
Agents, runs them, composes their results, and decides what to do next.

There is no workflow graph or second orchestration language. Use `let`, `if`,
functions, macros, `future`, `mapv`, and `run!`.

[![test](https://github.com/soohoonc/karcarthy/actions/workflows/test.yml/badge.svg)](https://github.com/soohoonc/karcarthy/actions/workflows/test.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

[Documentation](https://karcarthy.vercel.app/docs)

## One Agent

```clojure
(require '[karcarthy :as k])

(k/defagent assistant
  {:model "gpt-5.6"
   :instructions "Answer clearly and concisely."
   :input string?
   :output string?})

(def result (k/run! assistant "Explain continuation-passing style."))
(:output result) ;=> "..."
```

`agent` and `defagent` create plain maps. Their configuration is flat, so
ordinary data operations work as expected:

```clojure
(assoc assistant :instructions "Answer in one sentence.")
```

A model ID string uses OpenAI Responses. Pass a model map for transport,
provider, reasoning, streaming, or timeout options.

## Dynamic workflows are Clojure

Every Agent can call `eval` with `code` and `input`. For example, an Agent can
write this expression:

```clojure
(let [reviewer (agent {:name "reviewer"
                       :model "gpt-5.6"
                       :instructions "Find the riskiest assumption."
                       :input string?
                       :output string?})
      jobs (mapv #(future (run! reviewer %)) input)]
  (mapv (comp :output deref) jobs))
```

This is a real Clojure expression, not an Agent-shaped DSL node. It is read
with reader evaluation disabled, macroexpanded, and evaluated in the same JVM.
Its JSON-compatible value is returned to the model.

The first `run!` call establishes a run. Every `run!` within its dynamic
extent—including calls inside `future`—joins that run and shares its ID,
limits, usage, deadline, cancellation, approvals, events, context, and
executor. Each Agent still starts a fresh model conversation unless it is the
first call with a Session.

## Static composition is the same language

```clojure
(defn review-team [change]
  (->> [security-reviewer api-reviewer]
       (mapv #(future (k/run! % change)))
       (mapv deref)
       (mapv :output)))
```

The model writes the same kind of Clojure you would write ahead of time. That
is where Lisp's homoiconicity matters: code is data at the model boundary and
ordinary executable code after evaluation.

## Observe a run

```clojure
(def live (k/monitor {:display :tree}))
(def result (k/run! assistant "Complete the task." {:observe live}))
```

```text
Run run_7c2e9b… · running · 18s · 3 model calls · 8,421 tokens · 1 eval
└─ architect · waiting for Agent
   ├─ failure-analyst · calling model
   └─ rollout-planner · calling model
```

Runs return data with `:status`, `:output`, `:usage`, `:events`, and `:error`.
Configuration mistakes throw early; failures during execution become failed
Run maps with structured errors and events.

## Boundaries

karcarthy owns the bounded model/Tool loop. Clojure owns control flow. Sessions
own conversation history. Model transports translate I/O.

`eval` is full-trust, same-process Clojure. It is intentionally not a sandbox
for untrusted code. Model HTTP calls and process-backed Tools can still perform
external I/O or start processes; evaluating and coordinating Agents does not
start a new Clojure process.

## Examples

| Example | What it shows |
| --- | --- |
| [Basic](examples/basic/main.clj) | One Agent run |
| [Architect](examples/architect/main.clj) | An Agent writes a concurrent workflow |
| [Compose](examples/composition/main.clj) | The same workflow written ahead of time |
| [Coding](examples/coding/main.clj) | Repository work with local Tools |
| [Harbor](examples/harbor/README.md) | Evaluation with a recorded trajectory |

## Development

```bash
clojure -M:test
clojure -T:build all
cd docs
npm ci
npm run lint
npm run types:check
npm run build
```

karcarthy is MIT licensed.

# karcarthy

> **Agents write Clojure that runs Agents.**

karcarthy is a small, homoiconic agent library for Clojure. An Agent can answer
directly, call a Tool, or evaluate one ordinary Clojure expression that creates
and runs the team its task needs.

There is no workflow graph or second orchestration language. Use `let`, `if`,
functions, macros, `future`, `mapv`, and `run!`.

[![test](https://github.com/soohoonc/karcarthy/actions/workflows/test.yml/badge.svg)](https://github.com/soohoonc/karcarthy/actions/workflows/test.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

[Documentation](https://karcarthy.vercel.app/docs)

## Run one Agent

```clojure
(require '[karcarthy :as k])

(k/defagent assistant
  {:model "gpt-5.6"
   :instructions "Answer clearly and concisely."
   :input-schema string?
   :output-schema string?})

(def garden-report
  "A moon garden's leaves are turning silver. What should we check first?")

(def result (k/run! assistant garden-report))

(:output result) ;=> "..."
```

Agents are plain maps. Their operational configuration stays flat:

```clojure
(assoc assistant :instructions "Answer in one sentence.")
```

A model ID string uses OpenAI Responses. Pass a model map for transport,
provider, reasoning, streaming, or timeout options.

## Let the task choose the team

Every Agent receives a built-in `eval` Tool. The Tool accepts one `code` string;
the current Agent input is already bound to `input`.

For the moon-garden task, an Agent might write:

```clojure
(let [botanist (agent {:name "botanist"
                       :model "gpt-5.6"
                       :instructions "Look for biological causes in the evidence."
                       :input-schema string?
                       :output-schema string?})
      radiation-engineer
      (agent {:name "radiation-engineer"
              :model "gpt-5.6"
              :instructions "Look for radiation and shielding causes."
              :input-schema string?
              :output-schema string?})
      jobs (mapv #(future (run! % input))
                 [botanist radiation-engineer])]
  (mapv (comp output deref) jobs))
```

This is Clojure, not an Agent-shaped DSL node. karcarthy reads one expression
with reader evaluation disabled, macroexpands it, and evaluates it in the same
JVM. Its model-safe value returns to the calling Agent.

The first `run!` establishes a Run. Calls inside its dynamic extent—including
calls inside `future`—join that Run and share its ID, limits, usage, deadline,
cancellation, approvals, events, context, and executor. Each Agent still starts
a fresh model conversation.

## Write known workflows directly

If the team is known ahead of time, define the Agents once and use the same
language yourself:

```clojure
(defn diagnose-garden [report]
  (->> [botanist radiation-engineer]
       (mapv #(future (k/run! % report)))
       (mapv (comp k/output deref))))
```

That is where Lisp's homoiconicity matters: the model can produce ordinary
Clojure data, and the running program can execute it as ordinary Clojure code.

## Observe the Run

```clojure
(def live (k/monitor {:display :tree}))
(def result (k/run! assistant garden-report {:on-event live}))
```

Runs return data with `:status`, `:output`, `:usage`, `:events`, and `:error`.
Configuration mistakes throw before execution; runtime failures become failed
Run maps with structured errors and retained events.

## Trust boundary

`eval` is full-trust, same-process Clojure. It is not a sandbox for untrusted
code. Model transports and process-backed Tools can also perform external I/O or
start processes.

## Examples

| Example | What it shows |
| --- | --- |
| [Basic](examples/basic/main.clj) | One Agent Run |
| [Architect](examples/architect/main.clj) | A task selects and runs its own specialist team |
| [Compose](examples/composition/main.clj) | A known team written directly in Clojure |
| [Chat](examples/chat/main.clj) | Conversation history with a Session |
| [Coding](examples/coding/main.clj) | Repository work with local Tools |
| [Harbor](examples/harbor/README.md) | Evaluation with a behavioral verifier and trajectory |

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

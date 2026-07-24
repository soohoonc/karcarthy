# karcarthy

> **Agents write Clojure that runs Agents.**

karcarthy is a small, homoiconic agent library for Clojure. An Agent can answer
directly or call a Tool. When its tools include `k/eval`, it can also evaluate
one ordinary Clojure expression that creates and runs the team its task needs.

There is no workflow graph or second orchestration language. Use `let`, `if`,
functions, macros, `future`, `mapv`, and `run!`.

[![test](https://github.com/soohoonc/karcarthy/actions/workflows/test.yml/badge.svg)](https://github.com/soohoonc/karcarthy/actions/workflows/test.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

[Documentation](https://karcarthy.vercel.app/docs)

## Run one Agent

```clojure
(require '[karcarthy :as k])

(k/defagent reviewer
  {:model "gpt-5.6"
   :instructions "Review the proposed change. Report only concrete defects."})

(def change
  (str "Contract: count may be zero.\n"
       "```diff\n+def average(total, count):\n+    return total / count\n```"))

(def result (k/run! reviewer change))

(:output result) ;=> "..."
```

Agents are plain maps. Their operational configuration stays flat:

```clojure
(assoc reviewer :instructions "Return only the highest-severity finding.")
```

A model ID string uses OpenAI Responses. Pass a model map for transport,
provider, reasoning, streaming, or timeout options.

## Let the change choose its reviewers

Add `k/eval` to an Agent's `:tools` when it should choose its own orchestration.
The Tool accepts one `code` string; the current Agent input is already bound to
`input`.

```clojure
(k/agent {:name "review-orchestrator"
          :model "gpt-5.6"
          :instructions "Choose focused reviewers after reading the change."
          :tools [k/eval]})
```

For a scheduler change, an Agent might decide that correctness and concurrency
need separate reviews, then write:

```clojure
(let [correctness-reviewer
      (agent {:name "correctness-reviewer"
              :model "gpt-5.6"
              :instructions "Find concrete behavioral defects in this change."})
      concurrency-reviewer
      (agent {:name "concurrency-reviewer"
              :model "gpt-5.6"
              :instructions "Check whether concurrent workers can claim one job twice."})
      jobs (mapv #(future (run! % input))
                 [correctness-reviewer concurrency-reviewer])]
  (mapv (comp output deref) jobs))
```

This is Clojure, not an Agent-shaped DSL node. karcarthy reads one expression
with reader evaluation disabled, macroexpands it, and evaluates it in the same
JVM. Its model-safe value returns to the calling Agent.

The first `run!` establishes a Run. Calls inside its dynamic extent—including
calls inside `future`—join that Run and share its ID, limits, usage, deadline,
cancellation, approvals, events, context, and executor. Each Agent still starts
a fresh model conversation.

An evaluated reviewer can itself include `:tools [eval]` to create a nested
verifier. Eval is not inherited by dynamically created Agents.

Run that complete example with an API key set:

```bash
clojure -M:examples review
```

The command shows the live Run tree, then prints prioritized findings for a
self-contained scheduler diff with three known defects.

## Write a known review process directly

If the team is known ahead of time, define the Agents once and use the same
language yourself:

```clojure
(defn review-change [change]
  (->> [correctness-reviewer concurrency-reviewer test-reviewer]
       (mapv #(future (k/run! % change)))
       (mapv (comp k/output deref))))
```

That is where Lisp's homoiconicity matters: the model can produce ordinary
Clojure data, and the running program can execute it as ordinary Clojure code.

| | Code orchestration | Dynamic orchestration |
| --- | --- | --- |
| Reviewers chosen by | Application author | Orchestrator after reading the diff |
| Coordination stored as | Source code | Retained generated source and macroexpansion |
| Nested verification | Explicitly programmed | A reviewer can create another Agent recursively |

## Observe the Run

```clojure
(def live (k/monitor {:display :tree}))
(def result (k/run! reviewer change {:on-event live}))
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
| [Code review](examples/review/main.clj) | A diff selects its reviewers; one creates a nested verifier |
| [Code orchestration](examples/composition/main.clj) | A fixed review team written directly in Clojure |
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

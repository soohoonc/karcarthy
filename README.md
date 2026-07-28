# karcarthy

> **Agents as code.**

karcarthy is an experimental Clojure agent harness. It owns the model/tool loop,
and Clojure is the orchestration language.

An agent with `k/eval` can send one Clojure expression back to the harness
process. That expression can construct agents and tools, call `run!`, and use
normal Clojure. There is no separate workflow format between the code the model
writes and the code the harness runs.

[![test](https://github.com/soohoonc/karcarthy/actions/workflows/test.yml/badge.svg)](https://github.com/soohoonc/karcarthy/actions/workflows/test.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

[Documentation](https://karcarthy.vercel.app/docs)

## Run one agent

```clojure
(require '[karcarthy :as k])

(k/defagent reviewer
  {:model "gpt-5.6"
   :instructions
   "Review this pull request. Report concrete defects introduced by the patch
    and explain the evidence for each finding."})

(def result
  (k/run! reviewer
          "Review this change: an admin export route authenticates the caller
           but never checks the caller's role."))

(k/output result)
```

Only `reviewer` participates:

```text
run! reviewer
└─ reviewer → model → review
```

`run!` returns a map with `:status`, `:output`, `:usage`, `:events`, and
`:error`.

## Let the agent author a program

Give the same reviewer `k/eval` when the input should determine which
specialists run:

```clojure
(k/defagent reviewer
  {:model "gpt-5.6"
   :instructions
   "Review this pull request. Choose the specialist justified by the patch and
    use eval to author and run it. Pass candidate findings to an independent
    verifier and publish only verified defects."
   :tools [k/eval]})
```

The eval tool description teaches the model how to use `agent`, `run!`,
`output`, and the original `input`. For an authorization-sensitive patch, the
reviewer can author:

```clojure
(let [security-reviewer
      (agent {:name "security-reviewer"
              :model "gpt-5.6"
              :instructions
              "Review authentication, authorization, and sensitive-data access.
               Return only candidate vulnerabilities introduced by the patch."})
      candidates (output (run! security-reviewer input))
      verifier
      (agent {:name "verifier"
              :model "gpt-5.6"
              :instructions
              "Try to disprove each candidate against the requirements and code."})]
  (output
   (run! verifier
         (str input "\n\nCandidate findings:\n" candidates))))
```

The application did not predefine those two agents. The reviewer constructed
and ran them after reading the patch.

```text
run! reviewer
└─ reviewer
   └─ eval
      ├─ security-reviewer
      └─ verifier
```

Nested `run!` calls join the active run and share limits, usage, events,
cancellation, approvals, context, and the run ID. Each agent starts a fresh
model conversation with only its explicit input.

Eval is full-trust JVM Clojure. It is not a sandbox.

## Three orchestration boundaries

| Approach | Who defines the agents? | When? |
| --- | --- | --- |
| `:agents` | The application | Before the run |
| Application Clojure | The application | Before the input is handled |
| `k/eval` | The model | After reading the input |

All three use the same agent values and `run!`.

## Examples

Set `OPENAI_API_KEY` before running live examples.

```bash
clojure -M:examples basic "Say hello."
clojure -M:examples review
clojure -M:examples coding /workspace/project "Fix the failing tests."
```

| Example | What it shows |
| --- | --- |
| [Basic](examples/basic/main.clj) | One agent |
| [Code review](examples/review/main.clj) | A reviewer authors a security reviewer and verifier |
| [Application-authored review](examples/composition/main.clj) | A predefined review team coordinated with Clojure |
| [Chat](examples/chat/main.clj) | Conversation history with a session |
| [Coding](examples/coding/main.clj) | Repository work with local tools and optional eval |
| [Harbor](examples/harbor/README.md) | An isolated coding evaluation |

## Development

```bash
clojure -M:test
clojure -T:build jar
cd docs
npm ci
npm run lint
npm run types:check
npm run build
```

karcarthy is MIT licensed.

# Examples

Public examples use the live Responses transport. Fake models remain in the
offline unit tests, not in the example applications. Each example has its own
folder and `main.clj`; none of them are part of published library artifacts.

| Example | Command or source | Demonstrates |
| --- | --- | --- |
| Basic Agent | [`basic/main.clj`](basic/main.clj) | One live model Run |
| Code review | [`review/main.clj`](review/main.clj) | A diff selects its reviewers; one creates a nested verifier |
| Chat | [`chat/main.clj`](chat/main.clj) | Sessions and a terminal application |
| Code orchestration | [`composition/main.clj`](composition/main.clj) | A predefined code-review team in ordinary Clojure |
| Coding Agent | [`coding/main.clj`](coding/main.clj) | Open-ended repository work with optional dynamic orchestration |
| Harbor | [`harbor`](harbor) | Evaluate the Coding Agent with a verifier and trajectory |

Set `RESPONSES_API_KEY` or `OPENAI_API_KEY` before running an example. Use
`KARCARTHY_OPENAI_MODEL` to override the default model.

## Basic Agent

```bash
clojure -M:examples basic \
  "Contract: count may be zero. Review: def divide(total, count): return total / count"
```

## Code review

Run the smallest live demonstration of karcarthy's central idea:

```bash
clojure -M:examples review
```

The bundled input contains a scheduler contract, storage semantics, and a short
diff. The orchestrator explicitly includes `k/eval`; after reading the diff, it
creates two or three relevant reviewers and runs them concurrently. One reviewer
also explicitly includes eval and creates a nested `finding-verifier` to challenge
its strongest candidate. The Run monitor
redraws the tree as they work. See the [example README](review/README.md).

## Chat

Start a REPL, then load the terminal chat:

```clojure
(load-file "examples/chat/main.clj")
(example.chat/chat!)
```

## Code orchestration

[`composition/main.clj`](composition/main.clj) defines correctness,
concurrency, and test reviewers ahead of time. Load it from a REPL and call
`example.composition/review-change`. This is the application-defined comparison
to the dynamically orchestrated code-review example.

## Coding Agent

Run the live Coding Agent on an unfamiliar repository and an open-ended task:

```bash
clojure -M:examples coding /workspace/project \
  "Investigate the failing integration tests, fix the cause, and verify it."
```

The Agent inspects the repository, runs tests, chooses its own working strategy,
makes changes, and verifies the result. It can create other Agents when useful,
but the example does not force a topology.

Run the opt-in paid verification of the public live examples with:

```bash
KARCARTHY_LIVE=1 OPENAI_API_KEY=... clojure -M:live-test
```

## Harbor

The Harbor example packages the fixed Coding Agent, runs it on an isolated
repository task, invokes a behavioral verifier, and records an ATIF trajectory:

```bash
KARCARTHY_LIVE=1 OPENAI_API_KEY=... examples/harbor/run.sh
```

See [`harbor/README.md`](harbor/README.md) for the task, metric, and result
artifacts.

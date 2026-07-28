# Examples

Public examples use the live Responses transport. Fake models remain in the
offline unit tests, not in the example applications. Each example has its own
folder and `main.clj`; none of them are part of published library artifacts.

| Example | Command or source | Demonstrates |
| --- | --- | --- |
| Basic agent | [`basic/main.clj`](basic/main.clj) | One live model run |
| Code review | [`review/main.clj`](review/main.clj) | A reviewer authors a security reviewer and verifier |
| Chat | [`chat/main.clj`](chat/main.clj) | Sessions and a terminal application |
| Application-authored review | [`composition/main.clj`](composition/main.clj) | A predefined code-review team in ordinary Clojure |
| Coding agent | [`coding/main.clj`](coding/main.clj) | Open-ended repository work with optional model-authored programs |
| Harbor | [`harbor`](harbor) | Evaluate the coding agent with a verifier and trajectory |

Set `RESPONSES_API_KEY` or `OPENAI_API_KEY` before running an example. Use
`KARCARTHY_OPENAI_MODEL` to override the default model.

## Basic agent

```bash
clojure -M:examples basic \
  "Review this function; it should return None when count is zero: def average(total, count): return total / count"
```

## Code review

Run the smallest controlled live demonstration of karcarthy's central idea:

```bash
clojure -M:examples review
```

The bundled input contains a customer-export requirement, existing
authentication behavior, and a short diff. After reading the change, the
reviewer creates `security-reviewer`, passes its candidate findings to
`verifier`, and publishes only the verified result. The run monitor redraws the
tree as they work. See the
[example README](review/README.md).

## Chat

Start a REPL, then load the terminal chat:

```clojure
(load-file "examples/chat/main.clj")
(example.chat/chat!)
```

## Application-authored review

[`composition/main.clj`](composition/main.clj) defines `triager`,
`security-reviewer`, and `verifier` ahead of time. Load it from a REPL and call
`example.composition/review-change`. This is the application-defined comparison
to the dynamically orchestrated code-review example.

## Coding agent

Run the live coding agent on an unfamiliar repository and an open-ended task:

```bash
clojure -M:examples coding /workspace/project \
  "Investigate the failing integration tests, fix the cause, and verify it."
```

The agent inspects the repository, runs tests, chooses its own working strategy,
makes changes, and verifies the result. It can author other agents and their
coordination when useful, but the example does not force a topology.

Run the opt-in paid verification of the public live examples with:

```bash
KARCARTHY_LIVE=1 OPENAI_API_KEY=... clojure -M:live-test
```

## Harbor

The Harbor example packages the fixed root coding agent definition, runs it on
an isolated repository task, invokes a behavioral verifier, and records an ATIF
trajectory:

```bash
KARCARTHY_LIVE=1 OPENAI_API_KEY=... examples/harbor/run.sh
```

See [`harbor/README.md`](harbor/README.md) for the task, metric, and result
artifacts.

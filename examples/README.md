# Examples

Public examples use the live Responses transport. Fake models remain in the
offline unit tests, not in the example applications. Each example has its own
folder and `main.clj`; none of them are part of published library artifacts.

| Example | Command or source | Demonstrates |
| --- | --- | --- |
| Basic Agent | [`basic/main.clj`](basic/main.clj) | One live model Run |
| Architect | [`architect/main.clj`](architect/main.clj) | A task selects and runs its own specialist team |
| Chat | [`chat/main.clj`](chat/main.clj) | Sessions and a terminal application |
| Agent composition | [`composition/main.clj`](composition/main.clj) | A garden team predefined in Clojure |
| Coding Agent | [`coding/main.clj`](coding/main.clj) | Open-ended repository work with optional dynamic eval |
| Harbor | [`harbor`](harbor) | Evaluate the Coding Agent with a verifier and trajectory |

Set `RESPONSES_API_KEY` or `OPENAI_API_KEY` before running an example. Use
`KARCARTHY_OPENAI_MODEL` to override the default model.

## Basic Agent

```bash
clojure -M:examples basic \
  "A moon garden's leaves are turning silver. What should we check first?"
```

## Architect

Run the smallest live demonstration of karcarthy's central idea:

```bash
clojure -M:examples architect \
  "A moon garden's leaves are turning silver after a solar storm. Diagnose the likely causes."
```

After reading the task, the root uses `eval` to create two or three relevant
specialists and run them concurrently. The Run monitor redraws the live tree as
they work. See the [example README](architect/README.md) for operational details.

## Chat

Start a REPL, then load the terminal chat:

```clojure
(load-file "examples/chat/main.clj")
(example.chat/chat!)
```

## Agent composition

[`composition/main.clj`](composition/main.clj) defines the botanist and
radiation engineer ahead of time. Load it from a REPL and call
`example.composition/diagnose-garden`.

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

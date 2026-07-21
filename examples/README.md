# Examples

Public examples use the live Responses transport. Fake models remain in the
offline unit tests, not in the example applications. Each example has its own
folder and `main.clj`; none of them are part of published library artifacts.

| Example | Command or source | Demonstrates |
| --- | --- | --- |
| Basic Agent | [`basic/main.clj`](basic/main.clj) | One live model Run |
| Architect | [`architect/main.clj`](architect/main.clj) | A running Agent authors and calls a task-specific team |
| Chat | [`chat/main.clj`](chat/main.clj) | Sessions and a terminal application |
| Agent composition | [`composition/main.clj`](composition/main.clj) | Predefined Agents coordinated with Clojure |
| Coding Agent | [`coding/main.clj`](coding/main.clj) | Open-ended repository work with optional dynamic eval |
| Harbor | [`harbor`](harbor) | Evaluate the Coding Agent with a verifier and trajectory |

Set `RESPONSES_API_KEY` or `OPENAI_API_KEY` before running an example. Use
`KARCARTHY_OPENAI_MODEL` to override the default model.

## Basic Agent

```bash
clojure -M:examples basic "Explain what an Agent value is."
```

## Architect

Run the smallest live demonstration of karcarthy's central idea:

```bash
clojure -M:examples architect \
  "Review a migration from synchronous writes to a queue."
```

The parent uses `eval` to author one Clojure expression after seeing the task.
A Run monitor redraws the live tree while that expression creates two Agents,
runs them concurrently, and waits for their
answers. This terminal output is designed to be recorded directly as the
project's short GIF or video.
See the [recording guide](architect/README.md) for the shot sequence and export
settings.

## Chat

Start a REPL, then load the terminal chat:

```clojure
(load-file "examples/chat/main.clj")
(example.chat/chat!)
```

## Agent composition

[`composition/main.clj`](composition/main.clj) defines two reviewers that
run concurrently and an editor that combines their results. Load it from a
REPL and construct a team with `example.composition/review-system`.

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

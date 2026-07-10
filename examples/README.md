# Examples

Public examples use the live Responses transport. Fake models remain in the
offline unit tests, not in the example applications. `examples/src` is added
only by the `:examples` and `:live-test` aliases and is not part of published
library artifacts.

| Example | Command or source | Demonstrates |
| --- | --- | --- |
| Basic Agent | `clojure -M:examples basic <prompt>` | One live model Run |
| Chat | [`clojure/chat.clj`](clojure/chat.clj) | Sessions and a terminal application |
| Agent composition | [`clojure/composition.clj`](clojure/composition.clj) | Predefined Agents coordinated with Clojure |
| Coding Agent | `clojure -M:examples coding <directory> <task>` | Open-ended repository work with optional Agent generation |
| Harbor | [`harbor`](harbor) | Reflective search over executable Agent programs |

Set `RESPONSES_API_KEY` or `OPENAI_API_KEY` before running an example. Use
`KARCARTHY_OPENAI_MODEL` to override the default model.

## Basic Agent

```bash
clojure -M:examples basic "Explain what an Agent value is."
```

## Chat

Start a REPL, then load the terminal chat:

```clojure
(load-file "examples/clojure/chat.clj")
(example.chat/chat!)
```

## Agent composition

[`clojure/composition.clj`](clojure/composition.clj) defines two reviewers that
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

The Harbor example starts from the Coding Agent, then uses Harbor rewards,
verifier feedback, ATIF trajectories, and GEPA reflection to evolve complete
Clojure Agent programs on a real multi-task dataset:

```bash
KARCARTHY_LIVE=1 OPENAI_API_KEY=... examples/harbor/hillclimb.sh
```

Candidate selection uses mean validation reward; the selected Agent is evaluated
once on an untouched test split. See [`harbor/README.md`](harbor/README.md) for
the search loop, metrics, artifacts, and limitations.

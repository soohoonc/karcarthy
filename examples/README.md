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
| Coding Agent | `clojure -M:examples coding <directory> <task>` | Repository tools and a model-authored specialist |
| Harbor | [`harbor`](harbor) | Isolated evaluation of the same Coding Agent through ACP |

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

The Agent inspects the repository, runs tests, creates a focused specialist
based on the evidence it found, makes changes, and verifies the result.

Run the opt-in paid verification of the public live examples with:

```bash
KARCARTHY_LIVE=1 OPENAI_API_KEY=... clojure -M:live-test
```

## Harbor

The Harbor example packages the same Coding Agent factory behind ACP and runs
it in an isolated repository-debugging environment:

```bash
KARCARTHY_LIVE=1 OPENAI_API_KEY=... examples/harbor/hillclimb.sh
```

The script compares direct and specialist configurations by mean Harbor
verifier reward. See [`harbor/README.md`](harbor/README.md) for task validation,
the resulting scoreboard, artifacts, and the trajectory viewer.

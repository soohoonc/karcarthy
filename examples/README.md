# Examples

These examples form one path from the core model/Tool loop to metric-driven
evaluation. The documentation mirrors this order. `examples/src` is added only
by the `:examples` and `:test` aliases; it is not part of the library source
path or published artifacts.

| Example | Command or source | Requires | Demonstrates |
| --- | --- | --- | --- |
| 1. Hello | `clojure -M:examples hello "hello"` | Clojure | Offline model/Tool loop |
| 2. Chat | [`clojure/chat.clj`](clojure/chat.clj) | API key | Sessions and a terminal application |
| 3. Agent composition | [`clojure/composition.clj`](clojure/composition.clj) | API key | Clojure functions and concurrency as orchestration |
| 4. Dynamic Agents | `clojure -M:examples dynamic` | Clojure | Runtime generation and event lineage |
| 5. Hill Climbing | local command or [`harbor`](harbor) | Clojure; optionally Docker and Harbor | Metric-driven candidate selection |

## 1. Hello

Run the smallest offline proof from the repository root:

```bash
clojure -M:examples hello "hello"
```

A deterministic model calls an `uppercase` Tool and prints `HELLO`.

## 2. Chat

With `OPENAI_API_KEY` available, start a REPL and load the terminal chat:

```clojure
(load-file "examples/clojure/chat.clj")
(example.chat/chat!)
```

## 3. Agent composition

[`clojure/composition.clj`](clojure/composition.clj) defines two reviewers that
run concurrently and an editor that combines their results. Load it from a
REPL, construct a team with `example.composition/review-system`, and run the
returned Agent.

## 4. Dynamic Agents

Run the deterministic runtime-generation trace:

```bash
clojure -M:examples dynamic
```

The architect model calls karcarthy's built-in `agent` Tool. The command prints
the submitted source and the read, expansion, checking, evaluation, and Agent
event lineage.

## 5. Hill Climbing

Run the three-candidate search locally:

```bash
clojure -M:examples hill-climb
```

Repeat the same search in isolated Harbor tasks with verifier rewards, ATIF
trajectories, and Harbor's results viewer:

```bash
examples/harbor/hillclimb.sh
```

See [`harbor/README.md`](harbor/README.md) for requirements and artifacts.

## Additional compiler example

[`clojure/calculator/main.clj`](clojure/calculator/main.clj) is a
compact, low-level example of `compile-agent!`. It is useful when studying the
compiler API, but it is not another step in the main example path:

```bash
clojure -M -e '(load-file "examples/clojure/calculator/main.clj")'
```

The paid end-to-end test is also deliberately outside the example path. It
lets GPT-5.6 author and run a new Agent form:

```bash
KARCARTHY_LIVE=1 OPENAI_API_KEY=... clojure -M:live-test
```

For serving an Agent to another process, see the [ACP
documentation](../docs/content/docs/acp.mdx).

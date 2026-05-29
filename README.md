# karcarthy

> Homoiconic agent orchestration for Clojure.

**karcarthy** (a nod to [John McCarthy](https://en.wikipedia.org/wiki/John_McCarthy_(computer_scientist)))
is a small Clojure library for orchestrating *many* AI agents — where the
agents, tools, handoffs, and the orchestration workflow itself are all plain
Clojure **data** (EDN).

## The idea

Anthropic describes [dynamic workflows](https://code.claude.com/docs/en/workflows)
as a way to *"move the plan into code"*: a script — not the model's context —
holds the loop, the branching, and the intermediate results, so it can be read,
rerun, and scaled to hundreds of agents.

In a Lisp, **code is data**. So karcarthy takes that one step further: the plan
is a *value*. A workflow is an EDN structure you can generate, transform,
compose, inspect, serialize, diff, and rerun — using the whole of Clojure to
manipulate it. Plan-as-code *and* plan-as-data, at once.

```clojure
(require '[karcarthy.core :as k])

;; An agent is just a map.
(def researcher
  (k/agent "researcher" "Research questions thoroughly and cite sources."
           :model "sonnet"
           :tools ["WebSearch" "WebFetch"]))

;; Run it on a harness. The mock harness is fully offline — great for tests.
(k/run-agent (k/mock-harness) researcher "What changed in Node v22?")
;=> {:karcarthy/type :result, :ok? true, :agent "researcher",
;    :text "[researcher] What changed in Node v22?", :raw {:harness :mock}}
```

## Use an existing harness

karcarthy does **not** reimplement the inner agent loop (model calls + tool
execution). That is delegated to an existing **harness** behind a small
protocol, so the orchestration layer stays provider-agnostic:

- **`mock`** — offline, deterministic; for tests and developing orchestration.
- **`claude-cli`** *(planned)* — drives the [Claude Agent SDK](https://code.claude.com/docs/en/agent-sdk/overview)
  via `claude -p --output-format stream-json`. Agents are already JSON there
  (`--agents '{...}'`), so EDN → JSON is direct.
- **`openai-agents`** *(planned)* — the OpenAI Agents SDK.

## Orchestration patterns *(planned)*

The canonical patterns from Anthropic's
[*Building Effective Agents*](https://www.anthropic.com/research/building-effective-agents),
expressed as combinators over data + a harness:

- **chain** — prompt chaining (sequential steps)
- **route** — classify, then dispatch to a specialized agent
- **parallel** — fan out (sectioning) and gather; voting
- **orchestrate** — orchestrator-workers (dynamic fan-out)
- **refine** — evaluator-optimizer (refute / critique until convergence)

## Status

Early. Working today: the data model, the `Harness` protocol, and the offline
mock harness, all on **Maven-Central-only** dependencies (no Clojars required).

## Develop

Requires a JDK (21+) and the [Clojure CLI](https://clojure.org/guides/install_clojure).

```bash
clojure -M:test     # run the test suite
clojure -M:repl     # (add your own alias) a REPL
```

## License

Copyright © 2026. Distributed under the Eclipse Public License 2.0.

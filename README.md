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

;; An agent is just a map — `defagent` is sugar (the var name is the agent name,
;; and the instructions double as the docstring).
(k/defagent researcher
  "Research questions thoroughly and cite sources."
  :model "sonnet"
  :tools ["WebSearch" "WebFetch"])

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

## Orchestration is data

A **flow** is a plain EDN value — an agent (leaf) or a composite node — that the
`run-flow` interpreter executes against any harness. Because it's data, you can
build it, nest it, generate it, `clojure.walk` it, serialize it, and diff it.

```clojure
(require '[karcarthy.orchestrate :as o])

;; Route by a triage agent; the technical path drafts an answer then reviews it.
(def support-desk
  (o/route triage
           {"billing"   billing
            "technical" (o/chain technical reviewer)   ; sequential
            "general"   general}
           :default general))

(o/run-flow (k/mock-harness) support-desk "my deploy 500s intermittently")
;=> {:karcarthy/type :result, :ok? true, :text "...reviewed draft...", ...}

;; It's just data — transform it like any value (bump every agent to opus):
(clojure.walk/postwalk #(if (k/agent? %) (assoc % :model "opus") %) support-desk)
```

Patterns from Anthropic's
[*Building Effective Agents*](https://www.anthropic.com/research/building-effective-agents),
as combinators over data + a harness:

- **chain** — prompt chaining; threads each result into the next, short-circuits ✅
- **parallel** / **parallel\*** — fan out concurrently and gather (sectioning/voting) ✅
- **route** — classify (fn or agent), then dispatch to a specialized flow ✅
- **refine** — evaluator-optimizer; a worker drafts, an evaluator critiques, repeat until accepted ✅
- **orchestrate** — orchestrator-workers; a planner emits subtasks as data, fan out (bounded concurrency) and gather ✅

See it run, fully offline:

```bash
clojure -M -m karcarthy.demo
```

## Status

Early, but real. Working today, all on **Maven-Central-only** dependencies (no
Clojars required):

- the EDN data model, `clojure.spec` validation, and **`defagent` / `defflow`** sugar
- the `Harness` protocol with three adapters: an offline **mock** harness, a
  **`claude-cli`** harness that drives `claude -p` (verified end-to-end), and a
  **`command`** harness that wraps any CLI/local model as an agent
- **chain / parallel / route / refine / orchestrate** — all five canonical
  patterns over a data DSL

Next: streaming (`stream-json`), sessions/handoffs, and an OpenAI Agents harness.

## Develop

Requires a JDK (21+) and the [Clojure CLI](https://clojure.org/guides/install_clojure).

```bash
clojure -M:test            # run the offline test suite
KARCARTHY_LIVE=1 clojure -M:test   # also run the live `claude -p` integration test
clojure -M -m karcarthy.demo       # run the offline demo
```

## License

Copyright © 2026. Distributed under the Eclipse Public License 2.0.

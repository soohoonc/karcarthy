# karcarthy

> Homoiconic agent orchestration for Clojure.

[![test](https://github.com/soohoonc/karcarthy/actions/workflows/test.yml/badge.svg)](https://github.com/soohoonc/karcarthy/actions/workflows/test.yml)
[![Clojars Project](https://img.shields.io/clojars/v/io.github.soohoonc/karcarthy.svg)](https://clojars.org/io.github.soohoonc/karcarthy)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

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
- **`claude-cli`** — drives the [Claude Agent SDK](https://code.claude.com/docs/en/agent-sdk/overview)
  via `claude -p`. JSON or **streaming** (`stream-json` + an `:on-event`
  callback), plus `:resume`/sessions, model, tools, and permission options.
- **`command`** — wrap *any* CLI as an agent (prompt → stdin, stdout → result):
  a local model (`ollama run …`), `llm`, a script, or `cat`/`tr` in tests.
- **`openai`** — drives the OpenAI Agents SDK via a small bundled Python runner.

The same flow runs over any of them — just swap the harness.

### Streaming, sessions, handoffs

```clojure
(require '[karcarthy.harness.claude :as cc]
         '[karcarthy.session :as sess])

;; Stream events live as the agent works:
(k/run-agent (cc/claude-harness {:on-event #(println (:type %))}) researcher "…")

;; A multi-turn conversation with memory (threads the session id):
(sess/converse (cc/claude-harness {}) researcher
               ["My name is Ada." "What is my name?"])   ; turn 2 recalls "Ada"

;; A handoff passes context to another agent (it resumes the prior session):
(o/run-flow (cc/claude-harness {}) (o/handoff triage specialist) "…")
```

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
- **handoff** — pass control to another agent, threading the session so it inherits context ✅

See it run, fully offline:

```bash
clojure -M -m karcarthy.demo
```

## Agents that speak karcarthy

The payoff of homoiconicity: because agents and flows are EDN *data*, an agent
can **emit karcarthy as text**, and karcarthy parses it — with `clojure.edn`,
**data only, never `eval`** — validates it, and runs it. So agents can use the
language themselves to build and edit behavior at runtime.

```clojure
(require '[karcarthy.self :as self])

;; 1. An agent WRITES a flow (taught the DSL via `self/dsl-reference`), and
;;    karcarthy runs what it wrote — a homoiconic take on "dynamic workflows":
(self/run-authored harness designer "Research X from three angles, then merge")
;=> {:flow <the EDN flow the agent wrote>, :result <run result>, :author …}

;; 2. An agent EDITS ITS OWN definition at runtime, then acts with it:
(o/run-flow harness (self/evolve poet) "Become an expert, then write a line on Lisp")
;=> {:rounds 3, :patches [{:instructions "…"}], :evolved {…}, :text "…"}
```

In a real run ([`examples/self_modify.clj`](examples/self_modify.clj)), a `haiku`
agent evolved itself from *"a mediocre poet"* into *"an expert minimalist poet"*
over three self-issued patches, then wrote:

> McCarthy opened a parenthesis the universe has not yet closed.

A `registry` + `agent-ref` let a running flow re-point a *named* agent, so later
steps pick up the change — runtime-editable shared behavior. Parsing is strictly
data (`clojure.edn`), so an agent's output can reshape *behavior*, never execute
arbitrary code.

## Status

Early, but real. Working today, all on **Maven-Central-only** dependencies (no
Clojars required):

- the EDN data model, `clojure.spec` validation, and **`defagent` / `defflow`** sugar
- the `Harness` protocol with **four** adapters: **mock** (offline),
  **claude-cli** (drives `claude -p`, incl. **streaming**; verified end-to-end),
  **command** (wrap any CLI/local model), and **openai** (OpenAI Agents SDK)
- all five **Building Effective Agents** patterns — **chain / parallel / route /
  refine / orchestrate** — plus **handoff** and multi-turn **sessions**
  (`converse`), over a data DSL
- **`karcarthy.self`** — agents author flows (`run-authored`) and edit their own
  behavior (`evolve`) at runtime; a runtime-editable agent `registry`
- production hardening: subprocess **timeouts**, fault-isolated nodes, bounded
  concurrency, tolerant routing

Next: tool-call extraction for richer handoffs, structured outputs
(`--json-schema`), clj-kondo hooks for `defagent`/`defflow`, and a hosted
Managed Agents harness.

## Install

karcarthy is a plain `deps.edn` library. Depend on it straight from GitHub — no
build step required:

```clojure
;; deps.edn
io.github.soohoonc/karcarthy {:git/url "https://github.com/soohoonc/karcarthy"
                              :git/sha "<latest commit sha>"}
```

Or use the published Maven coordinate. Tagged releases are published to
[Clojars](https://clojars.org/io.github.soohoonc/karcarthy) by CI, and each
release also attaches a jar to the
[GitHub Releases](https://github.com/soohoonc/karcarthy/releases) page (see
[RELEASING.md](RELEASING.md)). Locally, `clojure -T:build install` installs it
into your `~/.m2`.

```clojure
io.github.soohoonc/karcarthy {:mvn/version "0.2.0"}
```

Needs JDK 21+. karcarthy itself pulls only `org.clojure/clojure` and
`org.clojure/data.json` (both on Maven Central). The `claude-cli` harness needs
the `claude` CLI on `PATH`; the `openai` harness needs `python3` with
`openai-agents` and `OPENAI_API_KEY`.

## Develop

Requires a JDK (21+) and the [Clojure CLI](https://clojure.org/guides/install_clojure).

```bash
clojure -M:test            # run the offline test suite
KARCARTHY_LIVE=1 clojure -M:test   # also run the live `claude -p` integration test
clojure -M -m karcarthy.demo       # run the offline demo

# a LIVE orchestrator-workers run (real, paid claude -p calls):
clojure -M -e '(load-file "examples/live_orchestrate.clj")'
```

**Driving real agents.** When pointing the `claude-cli` harness at sub-agents,
prefer `:system-prompt-mode :replace` (so the system prompt is *only* the
agent's instructions) and disable tools via `:extra-args ["--disallowedTools"
"..."]`. Otherwise agents inherit Claude Code's interactive persona and tend to
ask clarifying questions or wander into tool use. See
[`examples/live_orchestrate.clj`](examples/live_orchestrate.clj).

## License

[MIT](LICENSE) © 2026 soohoonc and the karcarthy contributors — use it freely.

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

Next: tool-call extraction for richer handoffs, structured outputs
(`--json-schema`), and a hosted Managed Agents harness.

## Install

karcarthy is a plain `deps.edn` library. Depend on it straight from GitHub — no
build step required:

```clojure
;; deps.edn
io.github.soohoonc/karcarthy {:git/url "https://github.com/soohoonc/karcarthy"
                              :git/sha "<latest commit sha>"}
```

Or build and install a jar to your local Maven repo, then depend on the version:

```bash
clojure -T:build install   # installs io.github.soohoonc/karcarthy 0.1.x into ~/.m2
```

```clojure
io.github.soohoonc/karcarthy {:mvn/version "0.1.0"}
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

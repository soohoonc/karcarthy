# karcarthy

> Homoiconic agent orchestration for Clojure.

[![test](https://github.com/soohoonc/karcarthy/actions/workflows/test.yml/badge.svg)](https://github.com/soohoonc/karcarthy/actions/workflows/test.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

**karcarthy** orchestrates many AI agents, with the agents, tools, handoffs, and
the workflow itself all plain Clojure **data** (EDN). The name splices
[Andrej Karpathy](https://en.wikipedia.org/wiki/Andrej_Karpathy) (LLMs) and
[John McCarthy](https://en.wikipedia.org/wiki/John_McCarthy_(computer_scientist))
(Lisp): *kar* + *carthy*.

## The idea

A workflow is a value, not a framework object. You can build one with functions,
write one out by hand, generate one programmatically, walk it with
`clojure.walk`, store it as EDN, and diff two versions. Same for the agents
inside it. That is the point of doing this in a Lisp: behavior is data, and data
is easy to inspect and change, even at runtime.

karcarthy does not implement the inner agent loop (model calls and tool
execution). It hands that to an external *harness* and stays focused on the
coordination layer above it.

```clojure
(require '[karcarthy.core :as k])

;; An agent is just a map. defagent is sugar: the var name becomes the agent
;; name, and the instructions double as the docstring.
(k/defagent researcher
  "Research questions thoroughly and cite sources."
  :model "sonnet"
  :tools ["WebSearch" "WebFetch"])

;; Run it on a harness. The mock harness is offline, which is handy for tests.
(k/run-agent (k/mock-harness) researcher "What changed in Node v22?")
;=> {:karcarthy/type :result, :ok? true, :agent "researcher",
;    :text "[researcher] What changed in Node v22?", :raw {:harness :mock}}
```

## Harnesses

A harness runs one agent's model/tool loop. karcarthy talks to it through a
small protocol, so the orchestration layer doesn't care which provider you use:

- **`mock`**: offline and deterministic, for tests and developing orchestration.
- **`claude-cli`**: drives the `claude -p` CLI. Buffered JSON or streaming
  (`stream-json` with an `:on-event` callback), plus sessions (`:resume`),
  model, tool, and permission options.
- **`command`**: wraps any CLI as an agent (prompt to stdin, stdout to result):
  a local model (`ollama run …`), `llm`, a shell script, or `cat`/`tr` in tests.
- **`openai`**: drives the OpenAI Agents SDK through a small bundled Python runner.

The same flow runs over any of them; you just swap the harness.

### Streaming, sessions, handoffs

```clojure
(require '[karcarthy.harness.claude :as cc]
         '[karcarthy.session :as sess])

;; Stream events as the agent works:
(k/run-agent (cc/claude-harness {:on-event #(println (:type %))}) researcher "…")

;; A multi-turn conversation with memory (threads the session id):
(sess/converse (cc/claude-harness {}) researcher
               ["My name is Ada." "What is my name?"])   ; turn 2 recalls "Ada"

;; A handoff passes context to another agent (it resumes the prior session):
(o/run-flow (cc/claude-harness {}) (o/handoff triage specialist) "…")
```

## Orchestration is data

A **flow** is an EDN value: an agent (a leaf) or a composite node. The `run-flow`
interpreter executes it against any harness. Since it's data, you can build,
nest, generate, walk, and serialize it freely.

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

;; It's data, so transform it like any value (bump every agent to opus):
(clojure.walk/postwalk #(if (k/agent? %) (assoc % :model "opus") %) support-desk)
```

The flow nodes, all composable over data and a harness:

- **chain**: run steps in sequence, feeding each result into the next; short-circuits on failure.
- **parallel** / **parallel\***: fan out concurrently (bounded) and gather.
- **route**: classify with a fn or agent, then dispatch to a downstream flow.
- **refine**: a worker drafts, an evaluator critiques, repeat until accepted.
- **orchestrate**: a planner emits subtasks as data; a worker handles each, fanned out and gathered.
- **handoff**: pass control to another agent, threading the session so it inherits context.

Run the offline demo:

```bash
clojure -M -m karcarthy.demo
```

## Agents that speak karcarthy

Because agents and flows are EDN data, an agent can emit karcarthy as text, and
karcarthy can run it. Parsing goes through `clojure.edn` (data only, never
`eval`), so an agent's output can reshape behavior but cannot execute arbitrary
code.

```clojure
(require '[karcarthy.self :as self])

;; 1. An agent writes a flow (it's taught the DSL via self/dsl-reference), and
;;    karcarthy parses and runs what it wrote.
(self/run-authored harness designer "Research X from three angles, then merge")
;=> {:flow <the EDN flow the agent wrote>, :result <run result>, :author …}

;; 2. An agent edits its own definition at runtime, then acts with it.
(o/run-flow harness (self/evolve poet) "Become an expert, then write a line on Lisp")
;=> {:rounds 3, :patches [{:instructions "…"}], :evolved {…}, :text "…"}
```

In a real run ([`examples/clojure/self_modify.clj`](examples/clojure/self_modify.clj)),
a `haiku` agent patched itself from *"a mediocre poet"* into *"an expert
minimalist poet"* over three rounds, then wrote:

> McCarthy opened a parenthesis the universe has not yet closed.

A `registry` plus `agent-ref` let a running flow re-point a named agent, so
later steps pick up the change.

## Other languages

karcarthy is Clojure, but it's on the JVM, so Java, Kotlin, and Scala can drive
it through the Clojure Java API. See [`examples/`](examples/) for runnable
Java (verified), Kotlin, and Scala versions of the same flow.

## Status

Early but usable. Everything below works today on Maven-Central-only
dependencies (no Clojars needed to build):

- the EDN data model, `clojure.spec` validation, and `defagent` / `defflow`
- the `Harness` protocol with four adapters: `mock`, `claude-cli` (with
  streaming, verified end to end), `command`, and `openai`
- the flow nodes chain, parallel, route, refine, orchestrate, plus handoff and
  multi-turn sessions (`converse`)
- `karcarthy.self`: agents author flows (`run-authored`) and edit their own
  behavior (`evolve`) at runtime, plus a runtime-editable agent `registry`
- hardening: subprocess timeouts, fault-isolated nodes, bounded concurrency,
  tolerant routing

Next: tool-call extraction for richer handoffs, structured outputs
(`--json-schema`), clj-kondo hooks for the macros, and a hosted Managed Agents
harness.

## Install

karcarthy is a plain `deps.edn` library. You can depend on it straight from git:

```clojure
;; deps.edn
io.github.soohoonc/karcarthy {:git/url "https://github.com/soohoonc/karcarthy"
                              :git/sha "<commit sha>"}
```

There's no published release yet (it's early). Build a jar with
`clojure -T:build jar`, or `clojure -T:build install` it into your local `~/.m2`
and depend on the version. When a release is cut, the
[release workflow](RELEASING.md) publishes to Clojars and attaches a jar to the
Releases page.

```clojure
io.github.soohoonc/karcarthy {:mvn/version "0.0.2"}
```

Needs JDK 21+. The library itself depends only on `org.clojure/clojure` and
`org.clojure/data.json`. The `claude-cli` harness needs the `claude` CLI on
`PATH`; the `openai` harness needs `python3` with `openai-agents` and
`OPENAI_API_KEY`.

## Develop

```bash
clojure -M:test                      # offline test suite
KARCARTHY_LIVE=1 clojure -M:test     # also the live claude -p integration test
clojure -M -m karcarthy.demo         # offline demo

# a live orchestrator-workers run (real, paid claude -p calls):
clojure -M -e '(load-file "examples/clojure/live_orchestrate.clj")'
```

When you point the `claude-cli` harness at sub-agents, prefer
`:system-prompt-mode :replace` (so the system prompt is only the agent's
instructions) and turn tools off via `:extra-args ["--disallowedTools" "..."]`.
Otherwise an agent inherits Claude Code's interactive persona and tends to ask
clarifying questions or wander into tool use. See
[`examples/clojure/live_orchestrate.clj`](examples/clojure/live_orchestrate.clj).

## License

[MIT](LICENSE), © 2026 soohoonc and the karcarthy contributors.

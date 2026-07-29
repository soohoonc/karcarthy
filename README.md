# karcarthy

> **Agents as code.**

karcarthy is an experimental Clojure agent harness. An agent combines a model
with instructions and tools, and the harness runs the model/tool loop.

Clojure is [homoiconic](https://clojure.org/reference/reader): Clojure programs
are represented by Clojure data structures. karcarthy uses this code-as-data
property to let a model write the Clojure code that coordinates its work.

When an agent has the `k/eval` tool, the model can submit one Clojure expression
with each eval call. That expression can use ordinary Clojure, construct agents
and tools, and call `run!`. The harness evaluates it in the same process. There
is no separate workflow language or workflow runner between the code the model
writes and the code the harness runs.

[![test](https://github.com/soohoonc/karcarthy/actions/workflows/test.yml/badge.svg)](https://github.com/soohoonc/karcarthy/actions/workflows/test.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

[Documentation](https://karcarthy.vercel.app/docs)

## Run one agent

Use the `defagent` macro to define an agent, then execute it with `run!`. For
example, we can define one reviewer for a pull request:

```clojure
(require '[karcarthy :as k])

(k/defagent reviewer
  {:model "gpt-5.6"
   :instructions
   "Review this pull request. Report concrete defects introduced by the patch
    and explain the evidence for each finding."})

(def review-request
  (str
   "Review PR #87: add a customer export endpoint\n\n"
   "Requirement: only users with the `:admin` role may export customer data.
    Other authenticated users must receive 403.\n\n"
   "Existing behavior: `auth/require-user` authenticates the request and returns
    the user. It does not check roles.\n\n"
   "```diff\n"
   "diff --git a/src/app/routes.clj b/src/app/routes.clj\n"
   "--- a/src/app/routes.clj\n"
   "+++ b/src/app/routes.clj\n"
   "@@ -18,0 +19,5 @@\n"
   "+\n"
   "+(defn export-customers [request]\n"
   "+  (let [user (auth/require-user request)]\n"
   "+    {:status 200\n"
   "+     :body (csv/encode (customers/all))}))\n"
   "```"))

(k/output (k/run! reviewer review-request))
```

This is one agent invocation. No other agents are created or called. `run!`
returns a map with the status, output, usage, events, and any error. `output`
returns the completed output:

```text
Require the admin role before exporting customers — src/app/routes.clj:21

`auth/require-user` rejects anonymous requests but accepts every authenticated
user. A non-admin user can therefore reach `customers/all` and receive the full
export. Check the `:admin` role before reading customer data and return 403 when
it is absent.
```

## Let the agent decide

Now give the same reviewer `k/eval` with revised instructions:

```clojure
(k/defagent reviewer
  {:model "gpt-5.6"
   :instructions
   "Review this pull request. After reading the patch, use eval to author and
    run a reviewer for the affected risk surface.
    Pass its candidate findings to an independent verifier. Publish only
    verified defects introduced by the patch."
   :tools [k/eval]})

(k/output (k/run! reviewer review-request))
```

`k/eval` becomes a model-facing tool whose description explains the available
Clojure API. It tells the model how to construct an agent with `agent`, run it
with `run!`, extract its result with `output`, and use the original request
through the bound symbol `input`. For this input, the reviewer could submit an
expression like this:

```clojure
(let [security-reviewer
      (agent
       {:name "security-reviewer"
        :model "gpt-5.6"
        :instructions
        "Review only the security consequences of this patch. Trace
         authentication, authorization, and sensitive-data access. Return only
         candidate vulnerabilities introduced by the patch, with evidence."})
      candidates (output (run! security-reviewer input))
      verifier
      (agent
       {:name "verifier"
        :model "gpt-5.6"
        :instructions
        "Try to disprove each candidate finding against the supplied
         requirements and code. Return CONFIRMED, REJECTED, or NEEDS CONTEXT
         for each one, with the exact evidence."})]
  {:candidates candidates
   :verification
   (output
    (run! verifier
          (str input "\n\nCandidate findings:\n" candidates)))})
```

The harness evaluates that expression in the same process:

```text
run! reviewer
└─ reviewer reads the patch
   └─ model calls eval with one Clojure expression
      ├─ construct and run security-reviewer
      └─ construct and run verifier
   └─ eval returns both results to reviewer
└─ reviewer writes the final review
```

Every nested `run!` call shares the limits, usage, events, cancellation,
approvals, context, and run ID of the top-level run. Each constructed agent
starts a fresh model conversation and receives only the input passed to
`run!`.

This is the central idea: an agent can author the Clojure program that
coordinates its work. Eval is full-trust JVM Clojure, not a sandbox.

## Three orchestration boundaries

| Approach | Who defines the agents? | When? |
| --- | --- | --- |
| `:agents` | The application | Before the run |
| Application Clojure | The application | Before the input is handled |
| `k/eval` | The model | After reading the input |

All three use the same agent values and `run!`.

## Examples

Set `OPENAI_API_KEY` before running live examples.

```bash
clojure -M:examples chat
clojure -M:examples coding /workspace/project \
  "Inspect the project and explain one documentation weakness. Do not edit files."
clojure -M:examples repl
```

| Example | What it shows |
| --- | --- |
| [Chat](examples/chat/main.clj) | A streamed conversation with session history |
| [Coding](examples/coding/main.clj) | Local repository tools, live progress, and `k/eval` |

## Development

```bash
clojure -M:test
clojure -T:build jar
cd docs
npm ci
npm run lint
npm run types:check
npm run build
```

karcarthy is MIT licensed.

# karcarthy

> **Agents as code.**

[![test](https://github.com/soohoonc/karcarthy/actions/workflows/test.yml/badge.svg)](https://github.com/soohoonc/karcarthy/actions/workflows/test.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

karcarthy is a Clojure library for building Agents that can write the program
that coordinates their own work.

Why Clojure? A Clojure program is made from ordinary Clojure data: lists,
vectors, maps, and symbols. A model can produce a Clojure expression as data,
and karcarthy can evaluate that expression directly. The expression can create
Agents, call them with `run!`, run independent work concurrently with `future`,
inspect the results, and decide what should happen next.

Without eval, you write the program that coordinates the Agents. With `k/eval`,
the model can write that program after it has seen the task. Both use the same
Agent values, Tools, and Clojure functions. There is no separate workflow
language or workflow runner.

[Documentation](https://karcarthy.vercel.app/docs)

## Quickstart

You need JDK 21, the Clojure CLI, and an OpenAI API key.

| Requirement | Check | Installation |
| --- | --- | --- |
| JDK 21 | `java --version` | [Install Eclipse Temurin 21](https://adoptium.net/temurin/releases/?version=21) |
| Clojure CLI | `clojure -Sdescribe` | [Install the Clojure CLI](https://clojure.org/guides/install_clojure) |
| OpenAI API key | `test -n "$OPENAI_API_KEY"` | [Create an API key](https://platform.openai.com/api-keys) |

Clone the repository and set your API key:

```bash
git clone https://github.com/soohoonc/karcarthy.git
cd karcarthy
export OPENAI_API_KEY="..."
```

### Try the coding agent

| Command | What it opens |
| --- | --- |
| `clojure -M:examples coding` | A multi-turn coding session rooted in the current directory |
| `clojure -M:examples coding cwd=/path/to/repo` | The same session rooted in another repository |
| `clojure -M:examples coding dynamic=true` | The coding agent with `k/eval`, allowing it to write and run Clojure during the task |

At the `you>` prompt, try:

```text
Inspect this repository. Explain how a run moves from the model to tools and
back, and cite the files you used. Do not edit files.
```

Then continue the same conversation:

```text
Now identify one place where the documentation does not match the
implementation.
```

The session streams the answer and maintains a live view of model calls, tools,
reasoning summaries, nested Agents, and token usage.

See [Build a coding agent](https://karcarthy.vercel.app/docs/guides/coding) for
approvals, session commands, dynamic eval, and three focused local tests.

## Run one Agent

Start a REPL from the repository:

```bash
clojure -M
```

Define a research Agent with a web-search Tool:

```clojure
(require '[karcarthy :as k])

(k/defagent researcher
  {:model "gpt-5.6"
   :instructions
   "Research the question thoroughly. Prefer primary sources, verify important
    claims, and return a concise report with citations."
   :tools [(k/responses-web-search)]})

(def question
  "Compare SQLite, DuckDB, and ClickHouse for local analytics. Explain when
   each is appropriate and support the comparison with primary sources.")

(k/output (k/run! researcher question))
```

This Agent performs the research in one model conversation. A possible run
looks like this:

```text
run! researcher
└─ researcher receives the question
   ├─ searches for SQLite sources
   ├─ searches for DuckDB sources
   ├─ searches for ClickHouse sources
   ├─ compares the evidence
   └─ returns a cited report
```

`run!` returns a Run map containing the status, output, usage, events, and any
error. `output` returns the completed output or throws when the Run failed.

## Give the Agent eval

Now add `k/eval` and tell the researcher when it should use it:

```clojure
(k/defagent researcher
  {:model "gpt-5.6"
   :instructions
   "Research the question thoroughly. Prefer primary sources, verify important
    claims, and return a concise report with citations. Use eval when the
    question benefits from independent lines of research."
   :tools [(k/responses-web-search)
           k/eval]})

(k/output (k/run! researcher question))
```

The researcher can still answer directly. It does not have to use eval.

If it decides that the comparison benefits from independent research, it can
call eval with one Clojure expression like this:

```clojure
(let [topics
      [["sqlite-researcher" "Focus on SQLite."]
       ["duckdb-researcher" "Focus on DuckDB."]
       ["clickhouse-researcher" "Focus on ClickHouse."]]
      jobs
      (mapv
       (fn [[name focus]]
         (future
           (output
            (run!
             (agent
              {:name name
               :model
               {:transport :responses
                :provider :openai
                :id "gpt-5.6"}
               :instructions
               "Research the assigned system. Prefer primary sources and
                return concise findings with citations."
               :tools [web_search]})
             (str input "\n\n" focus)))))
       topics)]
  (mapv deref jobs))
```

The expression is Clojure written by the model during the Run.

It creates three Agents, gives each one the available web-search Tool, starts
their independent work with `future`, waits for their results with `deref`, and
returns those results to the original researcher.

One possible Run now looks like this:

```text
run! researcher
└─ researcher receives the question
   └─ model calls eval with one Clojure expression
      ├─ sqlite-researcher searches and returns findings
      ├─ duckdb-researcher searches and returns findings
      └─ clickhouse-researcher searches and returns findings
   └─ eval returns the three reports
└─ researcher compares the evidence
└─ researcher returns the final cited report
```

The three research calls may run concurrently because the expression uses
`future`. Each Agent starts a fresh model conversation and receives only the
input passed to its `run!` call.

Agents created inside eval do not inherit Tools. The expression explicitly gives
each researcher `web_search`, which is available because the root Agent was
given `(k/responses-web-search)`.

## What happens underneath

The first `run!` establishes the Run and calls the root Agent’s model.

If the model calls an ordinary Tool, karcarthy executes the Tool, returns its
result to the model, and continues the model/tool loop. Provider-hosted Tools,
such as web search, execute at the model provider.

If the model calls eval, karcarthy reads one Clojure expression and evaluates it
in the current JVM process. Any `run!` calls made by that expression participate
in the existing Run rather than creating unrelated Runs.

Those nested calls share the root Run’s limits, usage, events, cancellation,
context, and monitoring. Their model conversations remain independent.

When the expression finishes, its value is returned to the root model as the
eval result. The root model can inspect it, perform more work, or produce the
final answer.

Eval executes full-trust JVM Clojure. It is a programming capability, not a
sandbox.

## Continue

- [Build a coding agent](https://karcarthy.vercel.app/docs/guides/coding)
- [Quickstart](https://karcarthy.vercel.app/docs/quickstart)
- [Agent orchestration](https://karcarthy.vercel.app/docs/agent-orchestration)
- [Running Agents](https://karcarthy.vercel.app/docs/running-agents)
- [Full documentation](https://karcarthy.vercel.app/docs)

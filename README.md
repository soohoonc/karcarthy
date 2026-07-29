# karcarthy

> **Agents as code.**

[![test](https://github.com/soohoonc/karcarthy/actions/workflows/test.yml/badge.svg)](https://github.com/soohoonc/karcarthy/actions/workflows/test.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

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

[Documentation](https://karcarthy.vercel.app/docs)

## Quickstart

You need JDK 21, the Clojure CLI, and an OpenAI API key.

| Requirement    | Check                       | Installation                                                                    |
| -------------- | --------------------------- | ------------------------------------------------------------------------------- |
| JDK 21         | `java --version`            | [Install Eclipse Temurin 21](https://adoptium.net/temurin/releases/?version=21) |
| Clojure CLI    | `clojure -Sdescribe`        | [Install the Clojure CLI](https://clojure.org/guides/install_clojure)           |
| OpenAI API key | `test -n "$OPENAI_API_KEY"` | [Create an API key](https://platform.openai.com/api-keys)                       |

Clone the repository and set your API key:

```bash
git clone https://github.com/soohoonc/karcarthy.git
cd karcarthy
export OPENAI_API_KEY="..."
```

### Try the coding agent

| Command                                        | What it opens                                                                        |
| ---------------------------------------------- | ------------------------------------------------------------------------------------ |
| `clojure -M:examples coding`                   | A multi-turn coding session rooted in the current directory                          |
| `clojure -M:examples coding cwd=/path/to/repo` | The same session rooted in another repository                                        |
| `clojure -M:examples coding dynamic=true`      | The coding agent with `k/eval`, allowing it to write and run Clojure during the task |

At the `you>` prompt, try:

```text
Explain this repository to me.
```

Then continue the same conversation however you want.

See [Build a coding agent](https://karcarthy.vercel.app/docs/guides/coding) for
approvals, session commands, dynamic eval, and three focused local tests.

## Run one Agent

For an interactive start, run the REPL from the repository:

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
   :tools [(k/web-search)]})

(def question
  "Compare SQLite, DuckDB, and ClickHouse for local analytics. Explain when
   each is appropriate and support the comparison with primary sources.")

(k/output (k/run! researcher question))
```

This defines and runs a simple `researcher` agent that will search online for some question passed in.

`run!` returns a Run map containing the status, output, usage, events, and any
error. `output` returns the completed output or throws when the Run failed.

## Give the Agent `k/eval`

Now add `k/eval` and tell the researcher when it should use it:

```clojure
(k/defagent researcher
  {:model "gpt-5.6"
   :instructions
   "Research the question thoroughly. Prefer primary sources, verify important
    claims, and return a concise report with citations. Use eval when the
    question benefits from independent lines of research."
   :tools [(k/web-search)
           k/eval]})

(k/output (k/run! researcher question))
```

The researcher can still answer directly. It does not have to use eval.

If it decides that the comparison benefits from independent research, it can
call eval to generate a Clojure expression like this:

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

The expression is just plain Clojure written by the model.

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
given `(k/web-search)`.

## Continue

- [Build a coding agent](https://karcarthy.vercel.app/docs/guides/coding)
- [Quickstart](https://karcarthy.vercel.app/docs/quickstart)
- [Agent orchestration](https://karcarthy.vercel.app/docs/agent-orchestration)
- [Running Agents](https://karcarthy.vercel.app/docs/running-agents)
- [Full documentation](https://karcarthy.vercel.app/docs)

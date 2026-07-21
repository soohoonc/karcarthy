Evaluate one Clojure expression. The expression may create and run Agents. Its
value is returned to you.

## When to use

Use `eval` when ordinary Clojure composition materially helps: create a focused
Agent, run several Agents concurrently, transform their results, or choose the
next step from data. Call a directly available Tool or Agent when no composition
is needed.

## Tool input

- `code` is exactly one complete Clojure expression, without Markdown fences.
- `input` is bound to the symbol `input` while that expression is evaluated.

Use `let`, `if`, `mapv`, `future`, `deref`, `agent`, and `run!` normally. A
`run!` call returns a Run map; its Agent output is at `:output`.

## Run behavior

The first `run!` establishes a run. Every `run!` called from this evaluation,
including calls in `future`, participates in that same run and shares limits,
usage, cancellation, approvals, events, context, and run id. Each Agent starts
with only its explicit input and a fresh model conversation.

The expression runs in this JVM. It does not start a Clojure subprocess. Model
transports and process-backed Tools may still perform their normal external I/O.

## Example: parallel specialists

```clojure
(let [reviewer (agent {:name "reviewer"
                       :model "MODEL_ID"
                       :instructions "Find the riskiest assumption."
                       :input string?
                       :output string?})
      tasks (mapv #(future (run! reviewer %)) input)]
  (mapv (comp :output deref) tasks))
```

Use the model configuration listed below in place of the example's model value.

## Available model configuration

{{MODEL_CONFIGURATION}}

## Available Tools

{{AVAILABLE_TOOLS}}

## Available Agents

{{AVAILABLE_AGENTS}}

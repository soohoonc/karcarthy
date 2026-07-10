Write and run a Clojure Agent program.

## When to use

Use this Tool when the task would materially benefit from a new specialist or custom workflow: independent research, parallel work, adversarial review, iteration until a condition is met, or coordination among multiple Agents.

## When not to use

Do not use it for a straightforward Tool call, a small sequence you can handle directly, or merely to restate the current task. If an existing Agent already matches the work, call that Agent directly. Do not duplicate work already in progress or refer to unavailable symbols.

## Tool input

- `source` is exactly one complete Clojure `(agent ...)` form.
- `input` is the complete value passed to that Agent.

The new Agent receives no other task information. Include everything it needs in its instructions and `input`.

## What to generate

Do not include Markdown fences, prose, `defagent`, or multiple top-level forms in `source`.

A model-driven Agent has this shape:

```clojure
(agent
  {:name "descriptive-name"
   :description "What this Agent is responsible for."
   :model {:transport :responses :id "MODEL_ID"}
   :instructions "A complete, self-contained assignment."
   :tools [available-tool ...]
   :agents [available-agent ...]
   :input INPUT_CONTRACT
   :output OUTPUT_CONTRACT})
```

An Agent implemented as a Clojure program has this shape:

```clojure
(agent
  {:name "descriptive-name"
   :input INPUT_CONTRACT
   :output OUTPUT_CONTRACT}
  [input]
  BODY ...)
```

The body receives exactly `input`. It may use ordinary Clojure, construct additional `(agent ...)` forms, call Agents with `run!`, branch, loop, or run independent work concurrently. `run!` returns a Run map; check `:status` before using `:output`.

## What the new Agent receives

The generated source is the new Agent's definition. When it runs, karcarthy passes it only the explicit `input`; it does not copy this conversation, your reasoning, previous Tool results, or Session history.

Run-local application context remains accessible to Clojure code and Tools through `context`, but karcarthy does not add that value to model messages. Include all prompt-level task information in the Agent's instructions and `input`.

Generated code can refer to Clojure core, the karcarthy forms described above, public vars from the current application namespace, and the available symbols listed below. Do not use an application var unless the instructions explicitly identify it.

## What happens

karcarthy reads `source` as a Clojure list, expands the real `agent` macro, evaluates it, verifies that it produced an Agent, validates `input`, and runs the Agent.

The Agent's final output is returned to you as this Tool result. It is not shown directly to the user. Inspect or synthesize it before answering. A generated model Agent receives this same `agent` Tool and may generate recursively.

If compilation or execution fails, the Tool returns a structured error. Correct the form only when the error is actionable; do not repeat the same invalid call.

## Example: one Clojure specialist

`source`:

```clojure
(agent
  {:name "sum-values"
   :input map?
   :output number?}
  [{:keys [values]}]
  (reduce + values))
```

`input`:

```clojure
{:values [10 20 12]}
```

## Available model configuration

{{MODEL_CONFIGURATION}}

## Available Tools

{{AVAILABLE_TOOLS}}

## Available Agents

{{AVAILABLE_AGENTS}}

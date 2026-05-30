# Toward a production-ready configuration system

karcarthy should stay small: a data-first orchestration layer, not another
full-stack agent runtime. The production target is a stable configuration
intermediate representation (IR) that can drive Claude Code, Codex, OpenAI
Agents SDK, PydanticAI, DSPy, local models, or small command runners.

The most useful outside inspiration from DSPy is not its Python API. It is the
separation between declarative task contracts, execution modules, metrics, and
compiled optimized state. karcarthy can apply that idea one level higher:
describe the agent graph as data, validate it, run it through policies and
optimizers, then hand each leaf to the right runner.

## What production-ready means

### 1. A versioned config contract

- Add a top-level `:karcarthy/version`.
- Define a recursive schema for every portable node and agent field.
- Provide canonical normalization so equivalent configs print the same way.
- Support EDN and JSON readers/writers with clear validation errors.
- Add migration functions between config versions.
- Separate portable config from host-only extension points such as Clojure
  functions.

### 2. Typed signatures, inspired by DSPy

DSPy signatures show why prompts should not be the only contract. karcarthy
should let an agent declare optional input and output fields:

```clojure
{:karcarthy/type :agent
 :name "researcher"
 :instructions "Research the question and cite sources."
 :signature {:inputs  {:question :string}
             :outputs {:answer :string
                       :citations [:vector :string]}}}
```

The immediate value is validation and routing. A route can require an enum output;
an orchestrator can require a vector of subtasks; a handoff can check that the
next agent accepts the prior output shape.

Longer term, signatures give optimizers and runners a stable target. A runner can
decide whether the best execution strategy is direct prediction, chain-of-thought,
ReAct/tool use, structured output, or a framework-native agent.

### 3. Named modules and extension points

Today some nodes accept raw Clojure functions for routing, gathering, planning,
or evaluation. That is convenient but not portable. Production config should
reference named extension points:

```clojure
{:karcarthy/type :parallel
 :branches [...]
 :gather {:ref :summarize-results}}
```

The application provides the registry. The config remains serializable, diffable,
and safe to inspect before execution.

The first version of this idea now exists in `karcarthy.dynamic`: a controller
agent can define and patch named agents/workflows through EDN operations, and
workflows can late-resolve `:agent-ref` and `:workflow-ref` data references.
The production version should harden this with schema versioning, persistence,
and policy checks.

### 4. Metrics and optimization

DSPy treats metrics and examples as first-class inputs to optimization. karcarthy
should have the same slot in the IR:

```clojure
{:karcarthy/type :agent
 :name "triage"
 :instructions "Route support tickets."
 :signature {:inputs {:ticket :string}
             :outputs {:team [:enum "billing" "technical" "general"]}}
 :metric {:ref :triage-accuracy}}
```

An optimizer protocol can then compile a workflow into a new workflow:

- improve instructions while preserving schema and policy;
- choose cheaper or stronger runners per agent;
- add few-shot examples or structured-output constraints;
- save the compiled artifact with optimizer name, dataset hash, metric score,
  runner versions, and cost.

This should be explicit compilation, not silent runtime mutation.

### 5. Policy before execution

Self-editing is useful only when constrained. A production system needs:

- allowed patch keys for `evolve`;
- post-patch schema validation;
- per-runner and per-agent tool allowlists;
- MCP server declarations and per-tool approval modes;
- file, shell, and network sandbox policy;
- secrets handling rules;
- an audit log for policy decisions and agent-authored changes.

### 6. Durable execution

In-memory interpretation is enough for demos. Production workflows need:

- run IDs and node IDs;
- checkpointed node inputs, outputs, errors, and retry state;
- resumable interrupts for approvals;
- cancellation and deadlines;
- per-node retry, timeout, and backoff policy;
- idempotency keys for side-effecting tools;
- replay and fork for debugging.

### 7. Observability and evaluation

OpenTelemetry instrumentation is already present. The next layer is a stable run
event model:

- workflow, node, runner, tool, and optimizer events;
- redaction policy for prompts and outputs;
- cost, latency, token, and turn counters;
- eval reports for a workflow over a dataset;
- saved traces tied to compiled workflow artifacts.

### 8. Distribution and compatibility

Before calling this production-ready:

- publish releases instead of requiring git SHAs;
- document compatibility guarantees for config versions;
- add a CLI linter: `karcarthy validate workflow.edn`;
- add a small server mode for language-neutral callers;
- include real-runner examples for Claude, OpenAI, and a local command runner;
- keep offline tests as the default gate and add opt-in live conformance tests.

## Non-goals

- Reimplementing every provider's agent loop.
- Owning long-term memory, vector stores, or UI streaming directly.
- Making self-modification unconstrained.
- Treating prompt strings as the stable production API.

The useful production shape is a small, typed, policy-aware, optimizable IR for
agent orchestration.

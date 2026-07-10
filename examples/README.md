# Examples

[`clojure/chat.clj`](clojure/chat.clj) is a minimal terminal chat built from an
Agent, `run!`, and a Session. With `OPENAI_API_KEY` available, load it from a
REPL and start the loop:

```clojure
(load-file "examples/clojure/chat.clj")
(example.chat/chat!)
```

[`clojure/generated_calculator.clj`](clojure/generated_calculator.clj) runs
entirely offline and demonstrates:

- a contracted Clojure Tool;
- an Agent using the model/Tool loop;
- an Agent written as a Clojure program;
- model-authored Clojure read, expansion, checking, and evaluation;
- recursive Agent creation;
- Run events and Agent-form usage.

Run it from the repository root:

```bash
clojure -M -e '(load-file "examples/clojure/generated_calculator.clj")'
```

[`clojure/dynamic_agent.clj`](clojure/dynamic_agent.clj) is the most direct
runtime-generation proof. A deterministic model calls karcarthy's built-in
`agent` Tool with a new Clojure form, the harness compiles and runs it, and the
example prints the Agent/program event tree plus the submitted source:

```bash
clojure -M -e '(load-file "examples/clojure/dynamic_agent.clj")'
```

[`clojure/hill_climb.clj`](clojure/hill_climb.clj) evaluates three generated
Agent programs on the same exact-match metric and retains the best candidate:

```bash
clojure -M -e '(load-file "examples/clojure/hill_climb.clj")'
```

The [`harbor`](harbor) example runs the same candidates as isolated Harbor
jobs, using Harbor's verifier rewards, ATIF trajectories, and results viewer.

The paid end-to-end test lets GPT-5.6 write a new `agent` form and run it:

```bash
KARCARTHY_LIVE=1 OPENAI_API_KEY=... clojure -M:live-test
```

Any ACP client can launch
`clojure -M -m karcarthy.acp namespace/agent-var` and speak ACP over stdio.
See the dedicated [ACP documentation](../docs/content/docs/acp.mdx).

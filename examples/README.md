# Examples

The canonical example is
[`clojure/homoiconic.clj`](clojure/homoiconic.clj). It runs entirely offline
and demonstrates:

- a contracted Clojure Tool;
- a model-backed Agent using the native model/tool loop;
- a custom Agent body;
- model-authored Clojure read, expansion, checking, and evaluation;
- recursive child invocation;
- run events and generated-form usage.

Run it from the repository root:

```bash
clojure -M -e '(load-file "examples/clojure/homoiconic.clj")'
```

The paid end-to-end test lets GPT-5.6 call the `agent` form, generate a child
Agent, and run it:

```bash
KARCARTHY_LIVE=1 OPENAI_API_KEY=... clojure -M:live-test
```

Python and TypeScript applications can launch
`clojure -M -m karcarthy.acp namespace/agent-var` and speak ACP over stdio.
They are protocol clients rather than authors of JSON workflow graphs.

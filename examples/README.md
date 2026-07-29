# Examples

Set `OPENAI_API_KEY` before running either live example. Set
`KARCARTHY_OPENAI_MODEL` to override the default model.

## Chat

Run a small terminal chat that streams each response and keeps conversation
history in a `Session`:

```bash
clojure -M:examples chat
```

You can also start the example REPL and call the chat functions yourself:

```bash
clojure -M:examples repl
```

```clojure
(example.chat/ask! "Remember that my project is called karcarthy.")
(example.chat/ask! "What is my project called?")
(example.chat/reset-chat!)
```

## Coding

Run one agent against a local repository:

```bash
clojure -M:examples coding /workspace/project \
  "Inspect the project and explain one documentation weakness. Do not edit files."
```

The terminal displays the live run tree while the model reasons, calls local
tools, and finishes its answer. This example includes `k/eval`, so the model can
write a Clojure expression that creates and coordinates focused agents when the
task benefits from it.

The local tools include file-writing and shell capabilities. Use a disposable
repository for tasks that permit edits. Eval is full-trust JVM Clojure, not a
sandbox.

The [`harbor`](harbor) directory is an evaluation integration rather than
another introductory example.

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

Start an interactive coding session in the current repository:

```bash
clojure -M:examples coding
```

Use `cwd` to work somewhere else:

```bash
clojure -M:examples coding cwd=/workspace/project
```

The prompt keeps conversation history until you exit or run `/clear`.
`thinking>` streams the provider's reasoning summary, `tool>` reports local
tool calls, and `agent>` streams the answer. Use `/status`, `/diff`, `/clear`,
`/help`, and `/quit` while the session is running.

Reads and searches run directly. Writes, edits, and shell commands ask for
approval by default. `approval=never` disables those local-tool prompts.

Add the eval capability explicitly when you want the model to author a
Clojure program that can create and coordinate focused agents:

```bash
clojure -M:examples coding cwd=/workspace/project dynamic=true
```

Eval runs full-trust JVM Clojure. It is not a sandbox, and local-tool approvals
do not govern code evaluated through `k/eval`.

The [`harbor`](harbor) directory is an evaluation integration rather than
another introductory example.

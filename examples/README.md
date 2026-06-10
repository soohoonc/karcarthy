# Examples

karcarthy is a Clojure library that runs on the JVM, so any JVM language can
drive it. The Java, Kotlin, and Scala examples each build two agents, compose
them with `pipe`, and run the workflow through the offline mock runner - identical work in three
languages, to show the library is reachable from each.

## Clojure

- `clojure/launch.clj` - the complete launch-readiness tutorial:
  classify with an EDN route, branch reviewers, write a brief, and revise on
  EDN critique verdicts.
- `clojure/claude_dynamic_agents.clj` - a Claude dynamic-workflow style run:
  a lead agent can patch its own instructions, a planner creates parallel
  workstreams, workers verify each stream, and a critic accepts the memo.
- `clojure/openai_deep_research.clj` - wraps the OpenAI Responses API Deep
  Research shape as a karcarthy leaf runner. Offline by default; live calls are
  gated by `KARCARTHY_OPENAI_LIVE=1` and `OPENAI_API_KEY`.
- `clojure/deep_research.clj` - a Deep Research-shaped workflow:
  plan research tracks, investigate them in parallel, filter evidence, write a
  cited report, and critique/revise. Runs offline with canned evidence and
  includes an opt-in live `codex-runner` path.
- `clojure/rewrite.clj` - build a workflow once, rewrite the EDN to add
  model and instruction configuration, then run the rewritten value.
- `clojure/live.clj` - a live delegate/reduce run (paid `claude -p`).
- The offline demo ships in the library: `clojure -M -m karcarthy.demo`.

## Python and TypeScript (via the executable)

Non-JVM languages drive karcarthy through `bin/karcarthy`: send a workflow
described as JSON on stdin, get the result as JSON. The workflow is data, so the
language builds and can transform it before execution.

- `python/launch.py`
- `typescript/launch.ts`

```bash
python3 examples/python/demo.py            # offline (mock runner)
python3 examples/python/launch.py

bun run examples/typescript/demo.ts        # or: npx tsx … / ts-node …
npx tsx examples/typescript/launch.ts
```

Both build workflows as plain dicts/objects and run them through the offline
mock runner. Tool names in the launch examples are runner allowlists: they
must already exist in the selected Agent SDK, CLI, or MCP configuration. The
mock runner ignores tools.

## JavaScript

- `javascript/claude_dynamic_agents.mjs` - builds a dynamic Claude-style
  delegate/reduce/critic workflow as JSON and sends it through
  `bin/karcarthy json`. Offline by default; set `KARCARTHY_CLAUDE_LIVE=1` for
  the Claude runner.
- `javascript/openai_deep_research.mjs` - calls the OpenAI Responses API Deep
  Research shape directly with `background`, web search, code interpreter, and
  optional vector-store or MCP data sources. Offline by default; set
  `KARCARTHY_OPENAI_LIVE=1` and `OPENAI_API_KEY` for a live call.

```bash
node examples/javascript/claude_dynamic_agents.mjs --print
node examples/javascript/openai_deep_research.mjs
```

Build the standalone CLI once when you want the examples to avoid invoking the
Clojure CLI:

```bash
clojure -T:build uber
./bin/karcarthy agent echo --instructions "Echo the input." hi
./bin/karcarthy run examples/workflows/echo.json hi
./bin/karcarthy json < request.json
```

`bin/karcarthy` also honors `KARCARTHY_JAR=/path/to/karcarthy-0.0.2-standalone.jar`
and `KARCARTHY_BIN=/path/to/karcarthy` for tests or installed copies.

## Java (verified)

```bash
CP=$(clojure -Spath)
javac -d /tmp/karc -cp "$CP" examples/java/Demo.java
java  -cp "$CP:/tmp/karc" Demo
# ok?  true
# text [summarizer] [researcher] what is a monad?
```

## Kotlin

```bash
CP=$(clojure -Spath)
kotlinc -cp "$CP" examples/kotlin/Demo.kt -include-runtime -d /tmp/demo.jar
java -cp "$CP:/tmp/demo.jar" DemoKt
```

## Scala

```bash
CP=$(clojure -Spath)
scalac -cp "$CP" -d /tmp/karc-scala examples/scala/Demo.scala
java   -cp "$CP:/tmp/karc-scala" Demo
```

`clojure -Spath` puts the library and its deps on the classpath from source. To
use a packaged jar instead, run `clojure -T:build jar` and put
`target/karcarthy-0.0.2.jar` plus Clojure on the classpath.

The non-Clojure examples use the
[Clojure Java API](https://clojure.github.io/clojure/javadoc/clojure/java/api/Clojure.html):
`Clojure.var` resolves a library function, `IFn.invoke` calls it. The Java
example is verified here; Kotlin and Scala make the same calls.

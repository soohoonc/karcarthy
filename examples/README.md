# Examples

karcarthy is a Clojure library that runs on the JVM, so any JVM language can
drive it. The Java, Kotlin, and Scala examples each build two agents, compose
them with `pipe`, and run the workflow through the offline mock adapter - identical work in three
languages, to show the library is reachable from each.

## Clojure

- `clojure/launch.clj` - the complete launch-readiness tutorial:
  classify, map reviewers, reduce their notes, write a brief, and iterate on
  critique.
- `clojure/mappings.clj` - offline emulations of LangGraph,
  CrewAI, AutoGen, OpenAI Agents SDK, and Google ADK-style orchestration shapes.
- `clojure/openai/`, `clojure/crewai/`, `clojure/autogen/`, `clojure/langgraph/`
  - tiny runnable orchestration-pattern examples, with one folder per pattern.
- `clojure/live.clj` - a live map/reduce run (paid `claude -p`).
- The offline demo ships in the library: `clojure -M -m karcarthy.demo`.

## Python and TypeScript (via the executable)

Non-JVM languages drive karcarthy through `bin/karcarthy`: send a workflow
described as JSON on stdin, get the result as JSON. The workflow is data, so the
language builds and can transform it before execution.

The pattern examples also have executable-backed JSON versions:

- `python/launch.py`
- `typescript/launch.ts`
- `python/openai/`, `python/crewai/`, `python/autogen/`
- `typescript/openai/`, `typescript/crewai/`, `typescript/autogen/`

```bash
python3 examples/python/demo.py            # offline (mock adapter)
python3 examples/python/launch.py

bun run examples/typescript/demo.ts        # or: npx tsx … / ts-node …
npx tsx examples/typescript/launch.ts
```

Both build workflows as plain dicts/objects and run them through the offline
mock adapter. Tool names in the launch examples are adapter allowlists: they
must already exist in the selected Agent SDK, CLI, or MCP configuration. The
mock adapter ignores tools.

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

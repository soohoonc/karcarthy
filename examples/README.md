# Examples

karcarthy is a Clojure library that runs on the JVM, so any JVM language can
drive it. The Java, Kotlin, and Scala examples each build two agents, compose
them with `pipe`, and run the workflow through the offline mock adapter - identical work in three
languages, to show the library is reachable from each.

## Clojure

- `clojure/tutorial_launch_brief.clj` - the complete launch-readiness tutorial:
  classify, map reviewers, reduce their notes, write a brief, and iterate on
  critique.
- `clojure/orchestrator_emulations.clj` - offline emulations of LangGraph,
  CrewAI, AutoGen, OpenAI Agents SDK, and Google ADK-style orchestration shapes.
- `clojure/swarm/`, `clojure/crewai/`, `clojure/autogen/`, `clojure/langgraph/`
  - tiny runnable orchestration-pattern examples, with one folder per pattern.
- `clojure/live_orchestrate.clj` - a live map/reduce run (paid `claude -p`).
- `clojure/self_modify.clj` - an agent edits itself.
- The offline demo ships in the library: `clojure -M -m karcarthy.demo`.

## Python and TypeScript (via the JSON bridge)

Non-JVM languages drive karcarthy through `karcarthy.cli`: send a workflow
described as JSON on stdin, get the result as JSON. The workflow is data, so the
language builds (and could transform) it, and an agent can generate or edit one the
same way. See [`../COMPARISON.md`](../COMPARISON.md) for how this differs from
PydanticAI, Agno, and the Vercel AI SDK.

The pattern examples also have JSON-bridge versions:

- `python/tutorial_launch_brief.py`
- `typescript/tutorial_launch_brief.ts`
- `python/swarm/`, `python/crewai/`, `python/autogen/`
- `typescript/swarm/`, `typescript/crewai/`, `typescript/autogen/`

```bash
python3 examples/python/demo.py            # offline (mock adapter)
python3 examples/python/tutorial_launch_brief.py
python3 examples/python/demo.py --live     # + an agent that edits itself (real claude)

bun run examples/typescript/demo.ts        # or: npx tsx … / ts-node …
npx tsx examples/typescript/tutorial_launch_brief.ts
```

Both build a workflow as a plain dict/object and run it; `--live` runs an
`evolve` workflow where the agent rewrites its own instructions, all over the
bridge.

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

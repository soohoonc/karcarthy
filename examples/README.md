# Examples

karcarthy is a Clojure library that runs on the JVM, so any JVM language can
drive it. The Java, Kotlin, and Scala examples each build two agents, chain
them, and run the chain on the offline mock harness - identical work in three
languages, to show the library is reachable from each.

## Clojure

- `clojure/live_orchestrate.clj` - a live orchestrator-workers run (paid `claude -p`).
- `clojure/self_modify.clj` - an agent writes a flow, and an agent edits itself.
- The offline demo ships in the library: `clojure -M -m karcarthy.demo`.

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

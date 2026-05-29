// Using karcarthy from Kotlin, through the Clojure Java API (same calls as the
// verified Java example).
//
//   CP=$(clojure -Spath)
//   kotlinc -cp "$CP" examples/kotlin/Demo.kt -include-runtime -d /tmp/karc.jar
//   java -cp "$CP:/tmp/karc.jar" DemoKt

import clojure.java.api.Clojure
import clojure.lang.IFn

fun main() {
    val require = Clojure.`var`("clojure.core", "require")
    require.invoke(Clojure.read("karcarthy.core"))
    require.invoke(Clojure.read("karcarthy.orchestrate"))

    val agent       = Clojure.`var`("karcarthy.core", "agent")
    val mockHarness = Clojure.`var`("karcarthy.core", "mock-harness")
    val chain       = Clojure.`var`("karcarthy.orchestrate", "chain")
    val runFlow     = Clojure.`var`("karcarthy.orchestrate", "run-flow")
    val get         = Clojure.`var`("clojure.core", "get")

    val researcher = agent.invoke("researcher", "Research the question.")
    val summarizer = agent.invoke("summarizer", "Summarize in one line.")
    val flow       = chain.invoke(researcher, summarizer)

    val result = runFlow.invoke(mockHarness.invoke(), flow, "what is a monad?")
    println("ok?  " + get.invoke(result, Clojure.read(":ok?")))
    println("text " + get.invoke(result, Clojure.read(":text")))
}

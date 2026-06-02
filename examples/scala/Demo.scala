// Using karcarthy from Scala, through the Clojure Java API (same calls as the
// verified Java example).
//
//   CP=$(clojure -Spath)
//   scalac -cp "$CP" -d /tmp/karc-scala examples/scala/Demo.scala
//   java   -cp "$CP:/tmp/karc-scala" Demo

import clojure.java.api.Clojure

object Demo {
  def main(args: Array[String]): Unit = {
    val require = Clojure.`var`("clojure.core", "require")
    require.invoke(Clojure.read("karcarthy.core"))
    require.invoke(Clojure.read("karcarthy.orchestrate"))

    val agent       = Clojure.`var`("karcarthy.core", "agent")
    val mockAdapter = Clojure.`var`("karcarthy.core", "mock-adapter")
    val chain       = Clojure.`var`("karcarthy.orchestrate", "chain")
    val run         = Clojure.`var`("karcarthy.orchestrate", "run")
    val get         = Clojure.`var`("clojure.core", "get")

    val researcher = agent.invoke("researcher", "Research the question.")
    val summarizer = agent.invoke("summarizer", "Summarize in one line.")
    val workflow   = chain.invoke(researcher, summarizer)

    val result = run.invoke(mockAdapter.invoke(), workflow, "what is a monad?")
    println("ok?  " + get.invoke(result, Clojure.read(":ok?")))
    println("text " + get.invoke(result, Clojure.read(":text")))
  }
}

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
    val mockRunner = Clojure.`var`("karcarthy.core", "mock-runner")
    val pipe        = Clojure.`var`("karcarthy.orchestrate", "pipe")
    val run         = Clojure.`var`("karcarthy.orchestrate", "run")
    val hashMap     = Clojure.`var`("clojure.core", "hash-map")
    val get         = Clojure.`var`("clojure.core", "get")

    // Agents are plain EDN data, so from Scala they are simply read.
    val researcher = agent.invoke(Clojure.read(
      "{:name \"researcher\" :instructions \"Research the question.\"}"))
    val summarizer = agent.invoke(Clojure.read(
      "{:name \"summarizer\" :instructions \"Summarize in one line.\"}"))
    val workflow   = pipe.invoke(researcher, summarizer)

    val result = run.invoke(hashMap.invoke(
      Clojure.read(":runner"),   mockRunner.invoke(),
      Clojure.read(":workflow"), workflow,
      Clojure.read(":input"),    "what is a monad?"))
    println("ok?  " + get.invoke(result, Clojure.read(":ok?")))
    println("text " + get.invoke(result, Clojure.read(":text")))
  }
}

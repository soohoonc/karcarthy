// Using karcarthy (a Clojure library) from Java, through the Clojure Java API.
//
// Run from the repo root:
//   CP=$(clojure -Spath)
//   javac -d /tmp/karc-java -cp "$CP" examples/java/Demo.java
//   java  -cp "$CP:/tmp/karc-java" Demo
//
// Agents and flows are Clojure data; you build and run them by resolving the
// library's vars and invoking them. The mock harness keeps this offline.

import clojure.java.api.Clojure;
import clojure.lang.IFn;

public class Demo {
    public static void main(String[] args) {
        IFn require = Clojure.var("clojure.core", "require");
        require.invoke(Clojure.read("karcarthy.core"));
        require.invoke(Clojure.read("karcarthy.orchestrate"));

        IFn agent       = Clojure.var("karcarthy.core", "agent");
        IFn mockHarness = Clojure.var("karcarthy.core", "mock-harness");
        IFn chain       = Clojure.var("karcarthy.orchestrate", "chain");
        IFn runFlow     = Clojure.var("karcarthy.orchestrate", "run-flow");
        IFn get         = Clojure.var("clojure.core", "get");

        Object researcher = agent.invoke("researcher", "Research the question.");
        Object summarizer = agent.invoke("summarizer", "Summarize in one line.");
        Object flow       = chain.invoke(researcher, summarizer);

        Object result = runFlow.invoke(mockHarness.invoke(), flow, "what is a monad?");

        System.out.println("ok?  " + get.invoke(result, Clojure.read(":ok?")));
        System.out.println("text " + get.invoke(result, Clojure.read(":text")));
    }
}

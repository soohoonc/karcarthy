# Open-source harness emulations

Tiny offline examples that mirror common open-source agent harness shapes without
depending on those projects.

```bash
clojure -M -e '(load-file "examples/clojure/open-source-harnesses/swarm/demo.clj")'
clojure -M -e '(load-file "examples/clojure/open-source-harnesses/crewai/demo.clj")'
clojure -M -e '(load-file "examples/clojure/open-source-harnesses/autogen/demo.clj")'
clojure -M -e '(load-file "examples/clojure/open-source-harnesses/langgraph/demo.clj")'
```

Sources mirrored by shape:

- OpenAI Swarm examples: https://github.com/openai/swarm/tree/main/examples
- CrewAI examples: https://docs.crewai.com/examples/example
- AutoGen: https://github.com/microsoft/autogen
- LangGraph examples: https://github.com/langchain-ai/langgraph

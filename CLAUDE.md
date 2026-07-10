# karcarthy

Read [AGENTS.md](AGENTS.md) before changing this repository. It is the
authoritative guide for the native Clojure harness architecture, commands,
layout, and conventions.

In particular: do not reintroduce the removed Runner protocol, workflow node
DSL, EDN/JSON workflow interpreter, or separate dynamic subsystem. Generated
behavior is ordinary Clojure code evaluated by `karcarthy.eval`.

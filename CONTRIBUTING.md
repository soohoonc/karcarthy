# Contributing to karcarthy

Thanks for your interest! karcarthy is a small, data-first agent orchestration
library. See [CLAUDE.md](CLAUDE.md) for the architecture and conventions.

## Development

```bash
clojure -M:test              # tests (offline; no API key or network needed)
clojure -M -m karcarthy.demo # the offline demo
clojure -T:build jar         # build a jar into target/
```

## Guidelines

- Dependencies must resolve from **Maven Central** (no Clojars).
- Keep entities as plain data tagged with `:karcarthy/type`.
- Prefer pure, offline-testable functions; gate live/paid calls behind env vars
  (e.g. `KARCARTHY_LIVE`) so the default test run stays offline and free.
- Add a test for new behavior, and register new test namespaces in
  `test/karcarthy/test_runner.clj`.
- Adding a workflow node: a constructor in `src/karcarthy/orchestrate.clj` +
  a `run-node` defmethod + schema entries in `src/karcarthy/schema.clj` +
  tests. Nodes that parse model EDN replies should go through `elicit!` in
  `orchestrate.clj` so they get the `:edn-retries` self-repair path.
- Adding a runner: implement `karcarthy.core/Runner` and return a result map.

## Pull requests

Keep PRs focused and make sure `clojure -M:test` passes. By contributing you
agree your contributions are licensed under the project's [MIT license](LICENSE).

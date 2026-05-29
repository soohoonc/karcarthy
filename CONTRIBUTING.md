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
- Adding an orchestration node: a constructor + a `run-node` defmethod + tests.
- Adding a harness: implement `karcarthy.core/Harness` and return a result map.

## Pull requests

Keep PRs focused and make sure `clojure -M:test` passes. By contributing you
agree your contributions are licensed under the project's [MIT license](LICENSE).

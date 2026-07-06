# Contributing to karcarthy

Thanks for your interest! karcarthy is a small, data-first agent orchestration
library. See [CLAUDE.md](CLAUDE.md) for the architecture and conventions.

## Development

```bash
clojure -M:test              # tests (offline; no API key or network needed)
clojure -M -m karcarthy.demo # the offline demo
clojure -T:build jar         # build a jar into target/
cd docs && npm ci && npm run lint && npm run types:check && npm run build
```

## Guidelines

- Dependencies must resolve from **Maven Central** (no Clojars).
- Keep entities as plain data tagged with `:karcarthy/type`.
- Prefer pure, offline-testable functions; gate live/paid calls behind env vars
  (e.g. `KARCARTHY_LIVE`) so the default test run stays offline and free.
- Add a test for new behavior, and register new test namespaces in
  `test/karcarthy/test_runner.clj`.
- Adding a workflow node: field grammar in `src/karcarthy/workflow.clj` + a
  constructor and `run-node` defmethod in `src/karcarthy/orchestrate.clj` +
  tests. EDN/JSON schemas and the CLI allowlist are derived from the grammar.
  Nodes that parse model EDN replies should go through `elicit!` in
  `orchestrate.clj` so they get the `:edn-retries` self-repair path.
- Adding a runner: implement `karcarthy.core/Runner` and return a result map.

Live conformance tests are opt-in and may spend API credits. Set
`KARCARTHY_LIVE=1`; OpenAI additionally needs `OPENAI_API_KEY`, and ACP needs
`KARCARTHY_ACP_COMMAND` as an EDN argv vector such as `["claude-code-acp"]`.

## Pull requests

Keep PRs focused and make sure `clojure -M:test` passes. By contributing you
agree your contributions are licensed under the project's [MIT license](LICENSE).

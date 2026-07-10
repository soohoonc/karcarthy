# Contributing

karcarthy is pre-stable and intentionally small. Changes should sharpen the
native Clojure harness rather than add a second orchestration language.

## Before opening a pull request

```bash
clojure -M:test
clojure -T:build jar
cd docs
npm ci
npm run lint
npm run types:check
npm run build
```

The default test suite must remain offline and free. Put live model tests behind
an explicit environment flag and keep the underlying request/response builders
pure and offline-testable.

## Architectural rules

- Add Agent and Tool behavior to the native Runtime in
  `src/karcarthy/core.clj`.
- A provider integration is a narrow normalized model transport. It must not
  run tools or child Agents.
- Use normal Clojure for orchestration. Do not add workflow node constructors
  or an interpreter.
- Generated behavior goes through `karcarthy.eval`: read with reader evaluation
  disabled, expand, evaluate, verify `agent?`, and emit program events.
- Add contracts and structured failures at every new effect boundary.
- Add event coverage for behavior that matters to replay or evaluation.
- Preserve source and expanded forms.
- Add tests and register new test namespaces in
  `test/karcarthy/test_runner.clj`.

## Pull requests

Explain the behavior change, why it belongs in the kernel, the events and
failure modes it introduces, and how it was verified. Keep unrelated edits out
of the branch.

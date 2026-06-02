# Roadmap

karcarthy is a small Clojure experiment: workflows are EDN, Clojure rewrites
that EDN, and `run` interprets it through an adapter. Future work should make
that loop sharper, not broader.

## Keep sharpening

- Make workflow validation explain failures with paths.
- Add a printed canonical form for workflow EDN.
- Keep generated workflow parsing data-only: `clojure.edn`, validation, no
  `eval`.
- Improve the rewrite API only when it removes real repetition.
- Keep the CLI focused on running plain workflow JSON.

## Adapters

- Keep adapters thin: one agent in, one result map out.
- Add live conformance tests for Claude CLI and OpenAI only as opt-in tests.
- Avoid turning karcarthy into a tool host; tools stay in SDKs, CLIs, MCP
  config, or command adapters.

## Non-goals

- No compatibility layer for every agent framework.
- No separate mutable runtime language.
- No graph UI/runtime clone.
- No legacy aliases during this pre-stable phase.

# Roadmap

karcarthy is a small Clojure experiment: workflows are EDN, Clojure rewrites
that EDN, and `run` interprets it through a runner. Future work should make
that loop sharper, not broader.

## Keep sharpening

- Keep workflow validation aligned with constructors and schemas.
- Add a printed canonical form for workflow EDN.
- Keep generated workflow parsing data-only: `clojure.edn`, validation, no
  `eval`.
- Improve the rewrite API only when it removes real repetition.
- Keep the CLI focused on running plain workflow JSON.

## Runners

- Keep runners thin: one agent in, one result map out.
- Keep live conformance tests opt-in: the Claude CLI test already runs behind
  `KARCARTHY_LIVE`; add matching opt-in tests for the OpenAI, Codex, and ACP
  runners.
- Avoid turning karcarthy into a tool host; tools stay in SDKs, CLIs, MCP
  config, or subprocess-backed runners.

## Non-goals

- No compatibility layer for every agent framework.
- No separate mutable runtime language.
- No graph UI/runtime clone.
- No legacy aliases during this pre-stable phase.

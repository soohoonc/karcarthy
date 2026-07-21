# Code review example

This live example starts one code-review orchestrator with a proposed change.
After reading the diff, it creates the reviewers that the change needs and runs
them concurrently. One reviewer creates a nested finding verifier to challenge
its strongest candidate before the orchestrator writes the final review.

```bash
clojure -M:examples review
```

The bundled scheduler diff is self-contained and deliberately violates three
stated requirements. You can instead pass another diff and its contract as one
quoted argument.

Set `RESPONSES_API_KEY` or `OPENAI_API_KEY` before running it. Use
`KARCARTHY_OPENAI_MODEL` to override the default model.

The terminal monitor displays the Run tree while the reviewers work. The
command then prints a conventional review with prioritized findings and a
summary. See the [code review guide](https://karcarthy.vercel.app/docs/guides/review)
to inspect the generated Clojure and retained Run events.

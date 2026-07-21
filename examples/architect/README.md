# Architect example

This live example lets one Agent choose and run a small specialist team after it
reads the task.

```bash
clojure -M:examples architect \
  "A moon garden's leaves are turning silver after a solar storm. Diagnose the likely causes."
```

Set `RESPONSES_API_KEY` or `OPENAI_API_KEY` before running it. Use
`KARCARTHY_OPENAI_MODEL` to override the default model.

The terminal monitor redraws the Agent tree while the generated specialists run.
The command prints the synthesized answer when the Run completes.

For the generated expression, event inspection, and semantics, see the
[Architect guide](https://karcarthy.vercel.app/docs/guides/architect).

# Code review example

This live example starts one reviewer with a proposed change. After reading the
diff, the reviewer authors one Clojure expression that creates and
runs `security-reviewer`, then creates and runs `verifier` against its candidate
findings. The reviewer publishes only verified findings.

```bash
clojure -M:examples review
```

The bundled customer-export patch is self-contained and deliberately omits the
required administrator authorization check. The command also accepts another
diff and its requirements as one quoted argument; the reviewer should select the
specialist justified by that change.

Set `RESPONSES_API_KEY` or `OPENAI_API_KEY` before running it. Use
`KARCARTHY_OPENAI_MODEL` to override the default model.

The terminal monitor displays the run tree while the agents work. The
command then prints a conventional review with prioritized findings and a
summary. See the [code review guide](https://karcarthy.vercel.app/docs/guides/review)
to inspect the generated Clojure and retained run events.

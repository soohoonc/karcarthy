# Harbor evaluation

This directory packages the Coding Agent for a Harbor task. Harbor runs the
Agent against an isolated repository, executes a separate behavioral verifier,
and records the trajectory.

## Requirements

Install Docker, JDK 21, the Clojure CLI, and `uv`. A live run also needs
`OPENAI_API_KEY` and explicit paid-test opt-in.

## Validate the task without a model

```bash
uvx --python 3.13 --from harbor harbor run \
  --path examples/harbor/tasks/scheduler \
  --agent oracle \
  --jobs-dir examples/harbor/jobs \
  --job-name oracle \
  --yes
```

## Run the Coding Agent

```bash
KARCARTHY_LIVE=1 OPENAI_API_KEY=... examples/harbor/run.sh
```

Use `KARCARTHY_OPENAI_MODEL` to select another Responses-compatible model and
`KARCARTHY_ATTEMPTS` to repeat the evaluation.

Results are written under `examples/harbor/jobs`. Open them with:

```bash
uvx --python 3.13 --from harbor harbor view examples/harbor/jobs
```

The complete task, metric, process boundary, and trajectory guide are in
[Evaluate an agent](https://karcarthy.vercel.app/docs/guides/harbor).

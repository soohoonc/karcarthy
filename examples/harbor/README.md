# Harbor evaluation

This example packages one fixed karcarthy Coding Agent and evaluates it with
[Harbor](https://www.harborframework.com/).

It demonstrates one path:

```text
Harbor task
  -> packaged karcarthy ACP process
  -> fixed Coding Agent and repository Tools
  -> Harbor verifier
  -> reward, logs, and ATIF trajectory
```

There is no optimizer or training loop. Harbor runs the Agent, checks its work,
and records what happened.

## Task and metric

The bundled `scheduler` task contains a small SQLite-backed delivery scheduler
with transaction and retry bugs. The Agent receives a production symptom,
inspects the repository, edits it, and runs its tests.

After the Agent exits, Harbor runs a separate behavioral verifier. Its `reward`
metric is `1` when every hidden test passes and `0` otherwise. The oracle
solution is available only for validating the task and verifier; Harbor does
not expose it to the Coding Agent.

## Validate the task for free

```bash
uvx --python 3.13 --from harbor harbor run \
  --path examples/harbor/tasks/scheduler \
  --agent oracle \
  --jobs-dir examples/harbor/jobs \
  --job-name oracle \
  --yes
```

## Run the Coding Agent

Docker, JDK 21, the Clojure CLI, `uv`, and an OpenAI credential are required.
The live evaluation is explicitly opt-in because it incurs model cost:

```bash
KARCARTHY_LIVE=1 OPENAI_API_KEY=... examples/harbor/run.sh
```

Set `KARCARTHY_OPENAI_MODEL` to choose another Responses-compatible model and
`KARCARTHY_ATTEMPTS` to repeat the same evaluation.

The script builds a Harbor-only application archive, uploads it and a Java 21
runtime into the isolated task environment, and asks Harbor to run the fixed
Agent through ACP.

## Inspect the result

Harbor writes the verifier result, logs, ACP events, and ATIF trajectory under
`examples/harbor/jobs`. Open its viewer with:

```bash
uvx --python 3.13 --from harbor harbor view examples/harbor/jobs
```

The trajectory shows the model messages, repository Tool calls, observations,
usage, and any Agent-authored eval and participating Agent calls.

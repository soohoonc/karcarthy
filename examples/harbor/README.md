# Harbor Agent search

This example evolves complete karcarthy Agent programs against a real Harbor
dataset. It is modeled on three research patterns:

- [AFlow](https://openreview.net/forum?id=z5uVAKwmjf) searches executable
  code-represented workflows using evaluation feedback.
- [Meta Context Engineering](https://arxiv.org/abs/2601.21557) keeps a history
  of artifacts and results, generates one offspring, and retains the best on a
  validation split.
- [GEPA](https://arxiv.org/abs/2507.19457) reflects on trajectories and verifier
  feedback rather than learning from a scalar reward alone.

The candidate is not `direct` or `specialist`, and the harness does not require
an Agent call. A candidate is exactly one executable Clojure `(agent ...)` form.
It may remain a single autonomous Agent, create other Agents, use review and
revision, run work concurrently, or express another strategy with ordinary
Clojure. Harbor measures whether the resulting program solves tasks.

## Search loop

`hillclimb.sh` packages karcarthy and runs `optimize.py`:

1. Download a published Harbor dataset.
2. Make deterministic, disjoint train, validation, and test splits.
3. Start from [`seed.clj`](seed.clj), the ordinary Coding Agent.
4. Run candidate programs on sampled training tasks in isolated environments.
5. Give GEPA the reward, verifier output, and ATIF trajectory.
6. Reflect on failures and propose a complete new Clojure Agent form.
7. Compile the form before spending a Harbor rollout on it.
8. Select candidates by mean validation reward, never training reward.
9. Evaluate the selected program once on the untouched test split.

This is reflective evolutionary search over an Agent harness. It is not RL and
does not update model weights.

Candidate forms are evaluated as full-trust JVM Clojure before Harbor runs them
inside a task. Use a trusted optimizer and inspect retained candidates; this is
not a safe evaluator for adversarial source code.

## What is measured

The primary objective is the mean value of the Harbor verifier's `reward`
field across validation tasks:

```text
validation score = sum(task rewards) / number of validation tasks
```

Use `--metric` when a dataset exposes another named reward. Verifier output and
ATIF trajectories are qualitative feedback for reflection and debugging; they
do not add points merely because a candidate used more Agents or Tools.

The final test score estimates transfer after search. Keep it untouched during
optimization. Token usage, latency, model-call count, and Agent count are
recorded diagnostics, not part of the current scalar objective.

## Run it

Requirements are Docker, JDK 21, Clojure CLI, `uv`, and an OpenAI credential.
The default dataset is Terminal-Bench 2 with eight tasks in each split:

```bash
KARCARTHY_LIVE=1 OPENAI_API_KEY=... \
  examples/harbor/hillclimb.sh \
  --max-evals 40 \
  --max-iterations 5
```

Start with a smaller paid experiment when checking the integration:

```bash
KARCARTHY_LIVE=1 OPENAI_API_KEY=... \
  examples/harbor/hillclimb.sh \
  --dataset terminal-bench-sample@2.0 \
  --train-size 4 --val-size 3 --test-size 3 \
  --max-evals 8 --max-iterations 1 --workers 2
```

Run `examples/harbor/hillclimb.sh --help` for the dataset, split, model,
environment, concurrency, and output options.

The development adapter downloads the matching Linux Temurin 21 JRE once into
`~/.cache/karcarthy` and uploads it with the application. Benchmark task images
therefore do not need Java preinstalled.

## Artifacts

The default output directory is `examples/harbor/runs/search`:

```text
best.clj          selected executable Agent program
split.json        exact train, validation, and test task IDs
summary.json      best validation score and held-out test results
trials/           Harbor results, verifier logs, ACP events, and ATIF traces
...               GEPA candidate history and reflective search state
```

The source in `best.clj` is the most direct visualization of the discovered
workflow. The ATIF files show what each candidate actually did, including any
runtime `agent` calls and their source, inputs, and observations.

## Free packaging smoke test

The bundled `scheduler` task is only a quick check of its Harbor environment and
verifier. It is not the optimization benchmark. Its oracle makes no model call:

```bash
uvx --python 3.13 --from harbor harbor run \
  --path examples/harbor/tasks/scheduler \
  --agent oracle \
  --jobs-dir examples/harbor/jobs \
  --job-name oracle \
  --yes
```

`agent.py` is a development adapter that uploads the separately built karcarthy
application and the current candidate into each task. Harbor still owns the
environment, ACP session, verifier, artifacts, and ATIF conversion.

# Harbor Coding Agent demo

This example packages the live `karcarthy.examples.coding/coding-agent` factory
and evaluates it through Harbor's generic ACP runner.

The Agent receives a real model, repository-rooted `read`, `write`, `edit`,
`bash`, and `search` Tools, and karcarthy's built-in `agent` Tool. It inspects an
unfamiliar repository, runs tests, creates one focused specialist based on its
initial evidence, applies a fix, and verifies the result.

## Task

The included `scheduler` task is a small repository-debugging environment, not
a function-completion prompt. The Agent receives a production symptom: a
SQLite-backed delivery scheduler duplicates claims and retries failures in a
tight loop. It must inspect several repository files, run the public tests,
reason about transactions and retry state, edit the implementation, and pass a
separate behavioral verifier.

The task includes an oracle solution only for validating the environment and
verifier. Harbor does not expose that solution to the Coding Agent.

## Requirements

- Docker
- JDK 21 or newer
- Clojure CLI
- `uv`
- `RESPONSES_API_KEY` or `OPENAI_API_KEY`

## Validate the task

The oracle is free and does not call a model:

```bash
uvx --python 3.13 --from harbor harbor run \
  --path examples/harbor/tasks/scheduler \
  --agent oracle \
  --jobs-dir examples/harbor/jobs \
  --job-name oracle \
  --yes
```

## Run the live hill climb

The live evaluation is opt-in because it incurs model cost:

```bash
KARCARTHY_LIVE=1 OPENAI_API_KEY=... examples/harbor/hillclimb.sh
```

Set `KARCARTHY_OPENAI_MODEL` to choose another Responses-compatible model and
`KARCARTHY_ATTEMPTS` to collect repeated rollouts of the same task.

The script evaluates two configurations of the same Coding Agent factory:

- `direct` inspects, repairs, and verifies the repository itself.
- `specialist` additionally creates one model-authored Agent after inspection
  and uses its findings.

Harbor's verifier reward is the objective. The script computes each candidate's
mean reward, selects the highest-scoring configuration, and writes the complete
result to `examples/harbor/jobs/scoreboard.json`. If the means tie, the later
`specialist` candidate wins the tie; use more attempts for a less noisy result.

The script builds a dedicated example application uberjar and local ACP
distribution. `agent.py` uploads that distribution through a small development
adapter; Harbor still owns the task environment, ACP session, verifier, result,
and ATIF conversion.

## Inspect the run

Every trial contains its result, verifier logs, raw ACP events, and ATIF
trajectory. Open Harbor's viewer with:

```bash
uvx --python 3.13 --from harbor harbor view examples/harbor/jobs
```

The `specialist` trajectory shows the Coding Agent's repository Tools and its
`agent` Tool call, including the generated specialist's Clojure source,
explicit input, and result.

This is an evaluation and rollout example, not an RL implementation. Harbor can
supply tasks, rewards, and trajectories to an external training loop. A full
RL integration also needs prompt/completion token IDs or interception at the
model server; karcarthy currently reports aggregate token usage through ACP.

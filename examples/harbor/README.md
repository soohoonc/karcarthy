# Harbor hill-climbing demo

This example evaluates three runtime-generated karcarthy Agent programs with
Harbor 0.18.0. Each candidate receives the same three isolated tasks. Harbor's
test scripts award `1` for an exact `answer.txt` match and `0` otherwise; the
driver retains the candidate with the highest mean reward.

The candidates are deliberately small and deterministic:

| Candidate | Generated behavior | Expected reward |
| --- | --- | --- |
| `constant` | Always writes `alpha`. | `1/3` |
| `first-line` | Writes the first instruction line. | `0/3` |
| `target-parser` | Extracts the `TARGET=...` value. | `3/3` |

All three still exercise the full dynamic path:

```text
Harbor task
  -> karcarthy ACP process
  -> architect model calls the built-in agent Tool
  -> submitted Clojure form is read, expanded, checked, and evaluated
  -> generated Agent runs and writes answer.txt
  -> Harbor verifier produces a reward
  -> Harbor converts the ACP stream to ATIF trajectory.json
```

## Requirements

- Docker
- JDK 21 or newer
- Clojure CLI
- `uv`

## Run the search

From the repository root:

```bash
examples/harbor/hillclimb.sh
```

The script builds a dedicated example application uberjar and local ACP
distribution; it does not add the example Agent to karcarthy's library JAR. It
uploads the distribution through the small `local_acp_agent.py` development
adapter, runs one Harbor job per candidate, reads the verifier rewards from
each `result.json`, and writes
`examples/harbor/jobs/scoreboard.json`.

Harbor's public ACP registry correctly requires HTTPS distribution URLs. The
local adapter changes only archive installation; it subclasses Harbor's generic
ACP Agent, so session execution, permission handling, logs, and ATIF conversion
still use Harbor's implementation. A published release can remove this adapter
and use an ordinary `acp:karcarthy@<version>` registry entry.

The task image preinstalls Harbor's ACP Python runtime so repeated candidate
evaluations do not reinstall the same dependencies inside every trial.

Harbor currently needs Python 3.13 on macOS for a prebuilt `tokenizers` wheel,
so the script invokes it as:

```bash
uvx --python 3.13 --from harbor harbor ...
```

## Inspect the proof

Every trial directory contains:

```text
result.json
agent/acp-events.jsonl
agent/acp-summary.json
agent/acp.txt
agent/trajectory.json
verifier/reward.txt
```

Open Harbor's results and ATIF trajectory viewer with:

```bash
uvx --python 3.13 --from harbor harbor view examples/harbor/jobs
```

The `agent` Tool call in `trajectory.json` contains the generated Clojure source
and its explicit input. karcarthy's finer `:program/*` compilation events stay
in its internal Run event stream and are demonstrated by the offline example:

```bash
clojure -M:examples dynamic
```

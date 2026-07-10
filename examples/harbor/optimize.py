# /// script
# dependencies = ["harbor", "gepa"]
# requires-python = ">=3.12,<3.14"
# ///
"""Evolve complete karcarthy Agent programs against a Harbor dataset."""

import argparse
import asyncio
import hashlib
import json
import logging
import os
import random
import shutil
import subprocess
import tempfile
import uuid
from pathlib import Path

import gepa.optimize_anything as oa
from gepa.optimize_anything import (
    EngineConfig,
    GEPAConfig,
    ReflectionConfig,
    optimize_anything,
)
from harbor import TrialQueue
from harbor.models.environment_type import EnvironmentType
from harbor.models.trial.config import (
    AgentConfig,
    EnvironmentConfig,
    TaskConfig,
    TrialConfig,
    VerifierConfig,
)
from harbor.registry.client import RegistryClientFactory


HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
ARCHIVE = HERE / "dist" / "karcarthy-harbor.tgz"
SEED = HERE / "seed.clj"

OBJECTIVE = """\
Optimize an executable Clojure Agent program for long-horizon terminal tasks.
The primary objective is mean Harbor verifier reward on held-out validation
tasks. Preserve generality: improvements should transfer across tasks rather
than encode task answers.
"""

BACKGROUND = """\
The candidate is exactly one top-level `(agent ...)` Clojure form. It is not a
prompt fragment or a workflow graph. The form may use ordinary Clojure control
flow and the karcarthy symbols `agent`, `run!`, `future`, and `deref` to express
any workflow. `candidate-model` returns the configured live model and
`candidate-tools` returns repository-rooted read, write, edit, bash, and search
Tools. `coding-instructions` returns the seed Coding Agent instructions.

A candidate may change prompts, create Agents, add review or iteration, run
work concurrently, or remain a single autonomous Agent. Do not force a fixed
topology. The form must evaluate to an Agent accepting a string task and
returning a string. It must not contain `ns`, `require`, `def`, shell escapes,
task-specific answers, or more than one top-level form.

Each evaluation supplies scalar reward plus verifier output and an ATIF
trajectory. Use failures and trajectories to form a hypothesis about the
candidate, then propose a complete revised Agent form.
"""

log = logging.getLogger(__name__)
queue: TrialQueue
args: argparse.Namespace
validation_cache: dict[str, str | None] = {}


def task_name(item) -> str:
    if name := getattr(item.id, "name", None):
        return name
    if path := getattr(item.id, "path", None):
        return Path(path).name
    return str(item.id)


def split_tasks(items, train_size: int, val_size: int, test_size: int, seed: int):
    """Create deterministic, disjoint train/validation/test task splits."""
    ordered = sorted(items, key=task_name)
    random.Random(seed).shuffle(ordered)
    needed = train_size + val_size + test_size
    if len(ordered) < needed:
        raise ValueError(f"dataset has {len(ordered)} tasks; {needed} requested")
    train = ordered[:train_size]
    val = ordered[train_size : train_size + val_size]
    test = ordered[train_size + val_size : needed]
    return train, val, test


def read_artifact(root: Path, relatives: list[str], limit: int = 8000) -> str:
    for trial_dir in root.iterdir() if root.exists() else []:
        for relative in relatives:
            path = trial_dir / relative
            if path.is_file():
                text = path.read_text(errors="replace")
                if len(text) > limit:
                    return text[:limit] + f"\n... ({len(text)} characters total)"
                return text
    return ""


def validate_sync(candidate: str) -> str | None:
    with tempfile.TemporaryDirectory(prefix="karcarthy-candidate-") as directory:
        path = Path(directory) / "candidate.clj"
        path.write_text(candidate)
        result = subprocess.run(
            ["clojure", "-M:examples", "validate", str(path)],
            cwd=ROOT,
            text=True,
            capture_output=True,
            timeout=45,
            check=False,
        )
        if result.returncode == 0:
            return None
        return (result.stderr or result.stdout or "candidate validation failed")[-8000:]


async def validate(candidate: str) -> str | None:
    if candidate not in validation_cache:
        validation_cache[candidate] = await asyncio.to_thread(
            validate_sync, candidate
        )
    return validation_cache[candidate]


async def run_trial(candidate: str, example) -> dict:
    invalid = await validate(candidate)
    if invalid:
        return {
            "task_id": task_name(example),
            "reward": 0.0,
            "error": "Candidate did not compile",
            "verifier": "",
            "trajectory": invalid,
            "artifacts": "",
        }

    directory = Path(tempfile.mkdtemp(prefix="karcarthy-harbor-trial-"))
    try:
        task_dir = directory / "task"
        shutil.copytree(example.downloaded_path, task_dir)
        candidate_path = directory / "candidate.clj"
        candidate_path.write_text(candidate)
        candidate_id = hashlib.sha256(candidate.encode()).hexdigest()[:12]
        safe_task = "".join(
            character if character.isalnum() or character in "-_" else "-"
            for character in task_name(example)
        )
        trials = (
            Path(args.output).resolve()
            / "trials"
            / candidate_id
            / f"{safe_task}-{uuid.uuid4().hex[:8]}"
        )
        trials.mkdir(parents=True, exist_ok=True)

        agent_env = {"KARCARTHY_OPENAI_MODEL": args.agent_model}
        for name in ("RESPONSES_API_KEY", "OPENAI_API_KEY"):
            if value := os.environ.get(name):
                agent_env[name] = value

        config = TrialConfig(
            task=TaskConfig(path=task_dir),
            trials_dir=trials,
            agent=AgentConfig(
                import_path="agent:Agent",
                kwargs={
                    "archive_path": str(ARCHIVE),
                    "candidate_path": str(candidate_path),
                    "auth_policy": "disabled",
                },
                env=agent_env,
            ),
            environment=EnvironmentConfig(type=EnvironmentType(args.environment)),
            verifier=VerifierConfig(),
        )
        result = await queue.submit(config)
        rewards = result.verifier_result.rewards if result.verifier_result else {}
        exception = result.exception_info
        return {
            "task_id": task_name(example),
            "reward": float(rewards.get(args.metric, 0.0)),
            "error": exception.exception_message if exception else None,
            "verifier": read_artifact(
                trials,
                [
                    "verifier/test-stdout.txt",
                    "verifier/test-output.txt",
                    "verifier/stdout.txt",
                ],
            ),
            "trajectory": read_artifact(
                trials,
                ["agent/trajectory.json", "agent/acp.txt"],
            ),
            "artifacts": str(trials),
        }
    except Exception as error:
        return {
            "task_id": task_name(example),
            "reward": 0.0,
            "error": str(error),
            "verifier": "",
            "trajectory": "",
            "artifacts": "",
        }
    finally:
        shutil.rmtree(directory, ignore_errors=True)


async def evaluate(candidate: str, example):
    result = await run_trial(candidate, example)
    oa.log(
        f"[{result['task_id']}] reward={result['reward']} "
        f"error={result['error'] or 'none'}"
    )
    return result["reward"], {
        "Task": result["task_id"],
        "Verifier": result["verifier"],
        "Agent trajectory": result["trajectory"],
        "Error": result["error"] or "",
        "Artifacts": result.get("artifacts", ""),
    }


async def test_candidate(candidate: str, examples) -> dict:
    results = await asyncio.gather(*(run_trial(candidate, item) for item in examples))
    rewards = [result["reward"] for result in results]
    return {
        "metric": args.metric,
        "mean_reward": sum(rewards) / len(rewards) if rewards else 0.0,
        "tasks": results,
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--dataset",
        default="terminal-bench@2.0",
        help="Published Harbor dataset (default: %(default)s)",
    )
    parser.add_argument("--train-size", type=int, default=8)
    parser.add_argument("--val-size", type=int, default=8)
    parser.add_argument("--test-size", type=int, default=8)
    parser.add_argument("--split-seed", type=int, default=7)
    parser.add_argument("--metric", default="reward")
    parser.add_argument("--max-evals", type=int, default=40)
    parser.add_argument("--max-iterations", type=int, default=5)
    parser.add_argument("--minibatch-size", type=int, default=3)
    parser.add_argument("--workers", type=int, default=4)
    parser.add_argument(
        "--agent-model",
        default=os.environ.get("KARCARTHY_OPENAI_MODEL", "gpt-5.6"),
    )
    parser.add_argument("--reflection-model", default="openai/gpt-5.4")
    parser.add_argument("--environment", default="docker")
    parser.add_argument("--output", default=str(HERE / "runs" / "search"))
    parser.add_argument("--skip-test", action="store_true")
    return parser.parse_args()


def main() -> None:
    global args, queue
    args = parse_args()
    if os.environ.get("KARCARTHY_LIVE") != "1":
        raise SystemExit("Set KARCARTHY_LIVE=1 to authorize paid optimization.")
    if not (os.environ.get("RESPONSES_API_KEY") or os.environ.get("OPENAI_API_KEY")):
        raise SystemExit("Set RESPONSES_API_KEY or OPENAI_API_KEY.")
    if not ARCHIVE.is_file():
        raise SystemExit(f"Package the Harbor Agent first: {ARCHIVE}")

    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(message)s")
    output = Path(args.output).resolve()
    output.mkdir(parents=True, exist_ok=True)
    queue = TrialQueue(n_concurrent=args.workers)

    log.info("Downloading %s", args.dataset)
    items = asyncio.run(
        RegistryClientFactory.create().download_dataset(args.dataset)
    )
    train, val, test = split_tasks(
        items, args.train_size, args.val_size, args.test_size, args.split_seed
    )
    split = {
        "dataset": args.dataset,
        "seed": args.split_seed,
        "train": [task_name(item) for item in train],
        "validation": [task_name(item) for item in val],
        "test": [task_name(item) for item in test],
    }
    (output / "split.json").write_text(json.dumps(split, indent=2))

    result = optimize_anything(
        seed_candidate=SEED.read_text(),
        evaluator=evaluate,
        dataset=train,
        valset=val,
        objective=OBJECTIVE,
        background=BACKGROUND,
        config=GEPAConfig(
            engine=EngineConfig(
                max_metric_calls=args.max_evals,
                max_candidate_proposals=args.max_iterations,
                run_dir=str(output),
            ),
            reflection=ReflectionConfig(
                reflection_lm=args.reflection_model,
                reflection_minibatch_size=args.minibatch_size,
            ),
        ),
    )

    best = result.best_candidate
    (output / "best.clj").write_text(best)
    summary = {
        "dataset": args.dataset,
        "metric": args.metric,
        "best_validation_reward": result.val_aggregate_scores[result.best_idx],
        "best_candidate": "best.clj",
    }
    if not args.skip_test:
        queue = TrialQueue(n_concurrent=args.workers)
        summary["test"] = asyncio.run(test_candidate(best, test))
    (output / "summary.json").write_text(json.dumps(summary, indent=2))
    log.info("Best validation reward: %.3f", summary["best_validation_reward"])
    log.info("Best Agent: %s", output / "best.clj")
    log.info("Summary: %s", output / "summary.json")


if __name__ == "__main__":
    main()

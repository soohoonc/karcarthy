"""Small helper for running karcarthy from Python examples."""

from __future__ import annotations

import json
import os
import subprocess
from pathlib import Path
from typing import Any

REPO_ROOT = Path(__file__).resolve().parents[2]


def command() -> list[str]:
    override = os.environ.get("KARCARTHY_BIN")
    if override:
        return [override, "json"]

    launcher = REPO_ROOT / "bin" / "karcarthy"
    if launcher.exists():
        return [str(launcher), "json"]

    return ["clojure", "-M", "-m", "karcarthy.cli", "json"]


def run(
    workflow: dict[str, Any],
    input_text: str,
    runner: str = "mock",
    mock_responses: dict[str, str] | None = None,
) -> dict[str, Any]:
    request: dict[str, Any] = {"workflow": workflow, "input": input_text, "runner": runner}
    if mock_responses is not None:
        request["mock-responses"] = mock_responses

    proc = subprocess.run(
        command(),
        input=json.dumps(request),
        capture_output=True,
        text=True,
        cwd=REPO_ROOT,
    )
    if proc.returncode != 0:
        raise RuntimeError("karcarthy failed:\n" + proc.stderr)
    return json.loads(proc.stdout)

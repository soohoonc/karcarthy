#!/usr/bin/env python3
"""AutoGen-style round-robin group chat through the karcarthy JSON bridge."""

import json
import subprocess
import sys


def agent(name, instructions):
    return {"type": "agent", "name": name, "instructions": instructions}


def run(workflow, input_text):
    req = json.dumps({"workflow": workflow, "input": input_text, "adapter": "mock"})
    proc = subprocess.run(
        ["clojure", "-M", "-m", "karcarthy.cli"],
        input=req,
        capture_output=True,
        text=True,
    )
    if proc.returncode != 0:
        sys.exit("karcarthy CLI failed:\n" + proc.stderr)
    return json.loads(proc.stdout)


workflow = {
    "type": "chain",
    "steps": [
        agent("planner", "Plan the example."),
        agent("builder", "Build the example."),
        agent("reviewer", "Review the example."),
    ],
}

print("workflow:")
print(json.dumps(workflow, indent=2))
print("\nresult:")
print(run(workflow, "Plan a tiny orchestration demo.")["text"])

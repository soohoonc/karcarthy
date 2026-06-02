#!/usr/bin/env python3
"""AutoGen-style round-robin group chat through the karcarthy executable."""

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from karcarthy import run


def agent(name, instructions):
    return {"type": "agent", "name": name, "instructions": instructions}


workflow = {
    "type": "pipe",
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

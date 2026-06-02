#!/usr/bin/env python3
"""CrewAI-style sequential crew through the karcarthy executable."""

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
        agent("researcher/market", "Find the market signal."),
        agent("analyst/risk", "Name the main risk."),
        agent("writer/brief", "Write the final brief."),
    ],
}

print("workflow:")
print(json.dumps(workflow, indent=2))
print("\nresult:")
print(run(workflow, "Show why karcarthy is useful.")["text"])

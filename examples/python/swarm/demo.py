#!/usr/bin/env python3
"""OpenAI Swarm-style specialist routing through the karcarthy executable."""

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from karcarthy import run


def agent(name, instructions):
    return {"type": "agent", "name": name, "instructions": instructions}


workflow = {
    "type": "bind",
    "source": agent("triage", "Classify as refund, sales, or support."),
    "routes": {
        "refund": agent("refund", "Handle refunds."),
        "sales": agent("sales", "Handle sales questions."),
        "support": agent("support", "Handle general support."),
    },
    "default": agent("support", "Handle general support."),
}

print("workflow:")
print(json.dumps(workflow, indent=2))
print("\nresult:")
print(run(workflow, "I was charged twice and need a refund.")["text"])

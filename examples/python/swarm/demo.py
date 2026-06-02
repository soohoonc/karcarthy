#!/usr/bin/env python3
"""OpenAI Swarm-style triage handoff through the karcarthy JSON bridge."""

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
    "type": "route",
    "router": agent("triage", "Classify as refund, sales, or support."),
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

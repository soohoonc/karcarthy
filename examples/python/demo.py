#!/usr/bin/env python3
"""Drive karcarthy from Python.

karcarthy is a Clojure library, but a workflow is just data, so any language can
build one and run it. This builds workflows as plain Python dicts, sends them to
the karcarthy CLI bridge (`clojure -M -m karcarthy.cli`) as JSON, and reads the
JSON result back. The homoiconic part survives the boundary: Python constructs
(and could transform) the workflow as data, and an agent can author or edit a
workflow the same way.

Run from the repo root:
    python3 examples/python/demo.py          # offline (mock runner)
    python3 examples/python/demo.py --live    # also the self-editing agent (real claude calls)
"""
import json
import subprocess
import sys


def run(workflow, input_text, runner="mock"):
    req = json.dumps({"workflow": workflow, "input": input_text, "runner": runner})
    proc = subprocess.run(
        ["clojure", "-M", "-m", "karcarthy.cli"],
        input=req, capture_output=True, text=True,
    )
    if proc.returncode != 0:
        sys.exit("karcarthy CLI failed:\n" + proc.stderr)
    return json.loads(proc.stdout)


# 1) A workflow is data: a chain of two agents, run on the offline mock runner.
chain = {"type": "chain", "steps": [
    {"type": "agent", "name": "researcher", "instructions": "Research the question."},
    {"type": "agent", "name": "summarizer", "instructions": "Summarize in one line."},
]}
print("chain ->", run(chain, "what is a monad?")["text"])

# 2) An agent edits its own definition at runtime, across the bridge. This needs
#    a real model, so it uses the claude runner (run with --live).
if "--live" in sys.argv:
    evolve = {"type": "evolve",
              "agent": {"type": "agent", "name": "poet",
                        "instructions": "You are a mediocre poet."},
              "max-rounds": 3}
    res = run(evolve,
              "Improve yourself into an expert, then write one line about Lisp.",
              runner="claude")
    print("evolve rounds:", res.get("rounds"), "->", res.get("text"))

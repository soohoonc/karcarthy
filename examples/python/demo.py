#!/usr/bin/env python3
"""Drive karcarthy from Python.

karcarthy is a Clojure library, but a workflow is just data, so any language can
build one and run it. This builds workflows as plain Python dicts, sends them to
the karcarthy CLI bridge (`clojure -M -m karcarthy.cli`) as JSON, and reads the
JSON result back. The homoiconic part survives the boundary: Python constructs
(and could transform) the workflow as data.

Run from the repo root:
    python3 examples/python/demo.py
"""
import json
import subprocess
import sys


def run(workflow, input_text, adapter="mock"):
    req = json.dumps({"workflow": workflow, "input": input_text, "adapter": adapter})
    proc = subprocess.run(
        ["clojure", "-M", "-m", "karcarthy.cli"],
        input=req, capture_output=True, text=True,
    )
    if proc.returncode != 0:
        sys.exit("karcarthy CLI failed:\n" + proc.stderr)
    return json.loads(proc.stdout)


# 1) A workflow is data: a pipe of two agents, run on the offline mock adapter.
workflow = {"type": "pipe", "steps": [
    {"type": "agent", "name": "researcher", "instructions": "Research the question."},
    {"type": "agent", "name": "summarizer", "instructions": "Summarize in one line."},
]}
print("pipe ->", run(workflow, "what is a monad?")["text"])

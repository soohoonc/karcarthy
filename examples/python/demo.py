#!/usr/bin/env python3
"""Drive karcarthy from Python.

karcarthy is a Clojure library, but a workflow is just data, so any language can
build one and run it. This builds workflows as plain Python dicts and runs them
through the karcarthy executable. The homoiconic part survives the boundary:
Python constructs (and could transform) the workflow as data.

Run from the repo root:
    python3 examples/python/demo.py
"""
from karcarthy import run


# 1) A workflow is data: a pipe of two agents, run on the offline mock runner.
workflow = {"type": "pipe", "steps": [
    {"type": "agent", "name": "researcher", "instructions": "Research the question."},
    {"type": "agent", "name": "summarizer", "instructions": "Summarize in one line."},
]}
print("pipe ->", run(workflow, "what is a monad?")["text"])

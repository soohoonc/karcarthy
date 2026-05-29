#!/usr/bin/env python3
"""karcarthy <-> OpenAI Agents SDK bridge.

Reads one JSON request from stdin and writes exactly one JSON result to stdout:

    request:  {"name", "instructions", "input", "model"?}
    result:   {"ok": bool, "agent", "text"?, "error"?, "type"?}

Requires `pip install openai-agents` and OPENAI_API_KEY in the environment. All
error paths still emit a single JSON object so the Clojure side can parse them.
"""
import sys
import json


def emit(obj):
    sys.stdout.write(json.dumps(obj))
    sys.stdout.flush()


def main():
    try:
        req = json.load(sys.stdin)
    except Exception as e:  # noqa: BLE001
        emit({"ok": False, "error": "bad request JSON: %s" % e})
        return

    name = req.get("name", "agent")

    try:
        from agents import Agent, Runner
    except Exception as e:  # noqa: BLE001
        emit({"ok": False, "agent": name,
              "error": "openai-agents not installed: %s" % e,
              "type": type(e).__name__})
        return

    kwargs = {"name": name, "instructions": req.get("instructions", "")}
    if req.get("model"):
        kwargs["model"] = req["model"]

    try:
        agent = Agent(**kwargs)
        result = Runner.run_sync(agent, req.get("input", ""))
        text = result.final_output
        if not isinstance(text, str):
            text = str(text)
        emit({"ok": True, "agent": name, "text": text})
    except Exception as e:  # noqa: BLE001
        emit({"ok": False, "agent": name,
              "error": str(e), "type": type(e).__name__})


if __name__ == "__main__":
    main()

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

    def build_agent(spec):
        agent_kwargs = {
            "name": spec.get("name", "agent"),
            "instructions": spec.get("instructions", ""),
        }
        if spec.get("model"):
            agent_kwargs["model"] = spec["model"]
        if spec.get("handoff_description"):
            agent_kwargs["handoff_description"] = spec["handoff_description"]
        return Agent(**agent_kwargs)

    kwargs = {"name": name, "instructions": req.get("instructions", "")}
    if req.get("model"):
        kwargs["model"] = req["model"]

    handoffs = [build_agent(spec) for spec in req.get("subagents", [])]
    if handoffs:
        kwargs["handoffs"] = handoffs

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

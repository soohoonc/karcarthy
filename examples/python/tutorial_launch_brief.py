#!/usr/bin/env python3
"""Non-trivial launch-readiness tutorial through the karcarthy JSON bridge."""

import json
import subprocess
import sys


def agent(name, instructions):
    return {"type": "agent", "name": name, "instructions": instructions}


def run(workflow, input_text, adapter="mock", mock_responses=None):
    req = {"workflow": workflow, "input": input_text, "adapter": adapter}
    if mock_responses:
        req["mock-responses"] = mock_responses
    req = json.dumps(req)
    proc = subprocess.run(
        ["clojure", "-M", "-m", "karcarthy.cli"],
        input=req,
        capture_output=True,
        text=True,
    )
    if proc.returncode != 0:
        sys.exit("karcarthy CLI failed:\n" + proc.stderr)
    return json.loads(proc.stdout)


reviewers = {
    "type": "map",
    "branches": [
        agent("product-reviewer", "Review launch value and user impact."),
        agent("engineering-reviewer", "Review operational risk and rollout plan."),
        agent("security-reviewer", "Review policy, data, and abuse risk."),
        agent("support-reviewer", "Review support readiness and messaging."),
    ],
}

launch_brief = {
    "type": "iterate",
    "worker": {
        "type": "pipe",
        "steps": [
            reviewers,
            agent("brief-writer", "Turn reviewer notes into a launch brief."),
        ],
    },
    "evaluator": agent("critic", "Reply ACCEPT when the launch brief is complete."),
    "max-rounds": 2,
}

workflow = {
    "type": "bind",
    "source": agent("classifier", "Classify as launch or incident."),
    "routes": {
        "launch": launch_brief,
        "incident": agent("incident-responder", "Write an incident response plan."),
    },
    "default": launch_brief,
}

mock_responses = {
    "classifier": "launch",
    "product-reviewer": "Product: enterprise admins need this before rollout.",
    "engineering-reviewer": "Engineering: ship behind a flag and watch SSO latency.",
    "security-reviewer": "Security: no new sensitive data; retain audit logs.",
    "support-reviewer": "Support: publish setup docs and prepare rollback messaging.",
    "brief-writer": (
        "Decision: launch with a staged rollout.\n"
        "Risks: SSO latency, misconfigured tenants, support readiness.\n"
        "Next actions: owner signoff, feature flag, docs update, launch monitor."
    ),
    "critic": "ACCEPT",
    "incident-responder": "Incident plan: stabilize, communicate, then review.",
}


if __name__ == "__main__":
    result = run(
        workflow,
        "Prepare the launch brief for a new enterprise SSO feature.",
        mock_responses=mock_responses,
    )
    print(json.dumps({"workflow": workflow}, indent=2))
    print("\nresult:")
    print(result["text"])
    print(f"\nrounds: {result.get('rounds')} accepted? {result.get('accepted?')}")

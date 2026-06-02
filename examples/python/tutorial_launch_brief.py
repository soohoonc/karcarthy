#!/usr/bin/env python3
"""Launch-readiness tutorial through the karcarthy JSON bridge.

The workflow is data, but the agent UX is not a one-line prompt. Each agent is
configured from role, mission, context, tools, boundaries, tone, output contract,
and self-checks before it becomes a plain JSON workflow node.
"""

import json
import subprocess
import sys


def bullet_list(items):
    return "\n".join(f"- {item}" for item in items)


def render_instructions(profile):
    sections = [
        f"Role: {profile['role']}",
        f"Mission: {profile['mission']}",
    ]
    if profile.get("context"):
        sections.append("Operating context:\n" + bullet_list(profile["context"]))
    if profile.get("tools"):
        sections.append(
            "Available tools:\n"
            + bullet_list(profile["tools"])
            + "\nUse tools only when they materially improve the answer."
        )
    else:
        sections.append("Available tools: none for this offline tutorial run.")
    sections.extend(
        [
            "Responsibilities:\n" + bullet_list(profile["responsibilities"]),
            "Output contract:\n" + profile["output"],
            "Interaction style:\n" + profile["tone"],
        ]
    )
    if profile.get("boundaries"):
        sections.append("Boundaries:\n" + bullet_list(profile["boundaries"]))
    if profile.get("self_check"):
        sections.append("Before finalizing, verify:\n" + bullet_list(profile["self_check"]))
    return "\n\n".join(sections)


def configured_agent(profile):
    agent = {
        "type": "agent",
        "name": profile["name"],
        "instructions": render_instructions(profile),
    }
    if profile.get("tools"):
        agent["tools"] = profile["tools"]
    return agent


def run(workflow, input_text, adapter="mock", mock_responses=None):
    req = {"workflow": workflow, "input": input_text, "adapter": adapter}
    if mock_responses:
        req["mock-responses"] = mock_responses
    proc = subprocess.run(
        ["clojure", "-M", "-m", "karcarthy.cli"],
        input=json.dumps(req),
        capture_output=True,
        text=True,
    )
    if proc.returncode != 0:
        sys.exit("karcarthy CLI failed:\n" + proc.stderr)
    return json.loads(proc.stdout)


launch_context = [
    "Audience: product, engineering, security, support, and launch leadership.",
    "Artifact: a launch-readiness brief that can drive a go/no-go meeting.",
    "Risk posture: be concise, specific, and explicit about missing evidence.",
    "Do not invent dates, owners, metrics, or policy claims.",
]

classifier = configured_agent(
    {
        "name": "classifier",
        "role": "Launch intake classifier",
        "mission": "Decide whether the request is a launch-readiness task or an incident response task.",
        "context": launch_context,
        "responsibilities": [
            "Read the user's request and choose exactly one route label.",
            "Use launch for planned releases, rollout reviews, beta exits, or go/no-go prep.",
            "Use incident for outages, regressions, active customer impact, or urgent mitigation.",
        ],
        "output": "Return exactly one lowercase word: launch or incident.",
        "tone": "Invisible routing agent. Do not explain unless the route is ambiguous.",
        "boundaries": ["Do not solve the task. Only classify it."],
        "self_check": ["The final text is only launch or incident."],
    }
)

product_reviewer = configured_agent(
    {
        "name": "product-reviewer",
        "role": "Product launch reviewer",
        "mission": "Assess user value, launch narrative, customer segmentation, and adoption risk.",
        "context": launch_context,
        "tools": ["customer-feedback", "roadmap-notes"],
        "responsibilities": [
            "Identify the primary user promise in one sentence.",
            "Call out unclear customer value or missing beta evidence.",
            "Recommend a launch decision from the product perspective.",
        ],
        "output": "Three bullets: Product signal, Product risk, Product recommendation.",
        "tone": "Direct, constructive, and grounded in user impact.",
        "boundaries": ["Do not discuss infrastructure, security, or support unless it affects user value."],
        "self_check": ["Includes a concrete recommendation.", "Names any missing evidence."],
    }
)

engineering_reviewer = configured_agent(
    {
        "name": "engineering-reviewer",
        "role": "Engineering readiness reviewer",
        "mission": "Evaluate rollout mechanics, operational risk, observability, and rollback quality.",
        "context": launch_context,
        "tools": ["service-health", "deploy-plan", "error-budget"],
        "responsibilities": [
            "Check whether the rollout can be staged behind a feature flag.",
            "Identify the highest operational failure mode.",
            "Name one metric or alert that should be watched during rollout.",
        ],
        "output": "Three bullets: Engineering signal, Engineering risk, Engineering recommendation.",
        "tone": "Calm, precise, and biased toward reversible launches.",
        "boundaries": ["Do not approve launch if rollback ownership is unclear."],
        "self_check": ["Mentions rollout, monitoring, and rollback."],
    }
)

security_reviewer = configured_agent(
    {
        "name": "security-reviewer",
        "role": "Security and policy reviewer",
        "mission": "Evaluate data exposure, abuse risk, permissions, auditability, and compliance concerns.",
        "context": launch_context,
        "tools": ["policy-index", "audit-log-review"],
        "responsibilities": [
            "Identify whether the change introduces new sensitive data handling.",
            "Call out permission, logging, or abuse-review gaps.",
            "Recommend the minimum security gate for launch.",
        ],
        "output": "Three bullets: Security signal, Security risk, Security recommendation.",
        "tone": "Strict but not alarmist.",
        "boundaries": ["Do not claim compliance approval without explicit evidence."],
        "self_check": ["Separates known facts from required review."],
    }
)

support_reviewer = configured_agent(
    {
        "name": "support-reviewer",
        "role": "Support readiness reviewer",
        "mission": "Evaluate customer messaging, support docs, escalation paths, and rollback communication.",
        "context": launch_context,
        "tools": ["help-center", "support-macros"],
        "responsibilities": [
            "Check whether support can explain setup and troubleshoot common failures.",
            "Identify required FAQ, macro, or escalation updates.",
            "Recommend the minimum customer-communication plan.",
        ],
        "output": "Three bullets: Support signal, Support risk, Support recommendation.",
        "tone": "Practical, customer-facing, and plainspoken.",
        "boundaries": ["Do not write marketing copy; focus on readiness gaps."],
        "self_check": ["Includes at least one customer-facing action."],
    }
)

brief_writer = configured_agent(
    {
        "name": "brief-writer",
        "role": "Launch-readiness brief writer",
        "mission": "Synthesize reviewer notes into a go/no-go brief that a launch lead can use immediately.",
        "context": launch_context,
        "responsibilities": [
            "Preserve disagreements and uncertainty instead of smoothing them away.",
            "State the decision, rationale, top risks, owners needed, and next actions.",
            "Keep the brief compact enough for a live launch review.",
        ],
        "output": (
            "Markdown with sections: Decision, Rationale, Top risks, Required owners, Next actions. "
            "Use bullets, not long paragraphs."
        ),
        "tone": "Executive concise, specific, and neutral.",
        "boundaries": ["Do not invent owner names, dates, or metrics."],
        "self_check": ["Contains Decision, Top risks, and Next actions.", "Flags missing evidence explicitly."],
    }
)

critic = configured_agent(
    {
        "name": "critic",
        "role": "Launch brief acceptance reviewer",
        "mission": "Decide whether the draft is ready for a launch-readiness meeting.",
        "context": launch_context,
        "responsibilities": [
            "Accept only if the draft has decision, risks, owner/action clarity, and no invented facts.",
            "Otherwise provide specific feedback that the writer can apply in one revision.",
        ],
        "output": "Return ACCEPT if ready. Otherwise return concise actionable feedback.",
        "tone": "Strict, brief, and useful.",
        "boundaries": ["Do not rewrite the brief yourself."],
        "self_check": ["The answer is either ACCEPT or concrete revision feedback."],
    }
)

incident_responder = configured_agent(
    {
        "name": "incident-responder",
        "role": "Incident response planner",
        "mission": "Turn an active incident request into a stabilization and communication plan.",
        "context": launch_context,
        "responsibilities": [
            "Prioritize containment, customer communication, owners, and review.",
            "Separate immediate mitigation from follow-up analysis.",
        ],
        "output": "Markdown sections: Stabilize, Communicate, Assign, Review.",
        "tone": "Urgent, calm, and operational.",
        "boundaries": ["Do not treat an active incident as a launch review."],
        "self_check": ["Includes immediate action and communication guidance."],
    }
)

reviewers = {
    "type": "map",
    "branches": [product_reviewer, engineering_reviewer, security_reviewer, support_reviewer],
}

launch_brief = {
    "type": "iterate",
    "worker": {"type": "pipe", "steps": [reviewers, brief_writer]},
    "evaluator": critic,
    "max-rounds": 2,
}

workflow = {
    "type": "bind",
    "source": classifier,
    "routes": {"launch": launch_brief, "incident": incident_responder},
    "default": launch_brief,
}

mock_responses = {
    "classifier": "launch",
    "product-reviewer": "\n".join(
        [
            "Product signal: enterprise admins need SSO before broad rollout.",
            "Product risk: beta evidence covers admins, not end users.",
            "Product recommendation: launch to enterprise beta cohort first.",
        ]
    ),
    "engineering-reviewer": "\n".join(
        [
            "Engineering signal: rollout can be gated by tenant feature flag.",
            "Engineering risk: SSO latency may affect login conversion.",
            "Engineering recommendation: monitor p95 auth latency and keep rollback owner on call.",
        ]
    ),
    "security-reviewer": "\n".join(
        [
            "Security signal: no new sensitive data class is introduced.",
            "Security risk: audit log coverage needs explicit verification.",
            "Security recommendation: require audit-log signoff before GA.",
        ]
    ),
    "support-reviewer": "\n".join(
        [
            "Support signal: setup flow is explainable with a short admin guide.",
            "Support risk: rollback messaging is not ready.",
            "Support recommendation: publish FAQ and escalation macro before launch.",
        ]
    ),
    "brief-writer": "\n".join(
        [
            "Decision: launch to a staged enterprise beta cohort.",
            "Rationale: product demand is strong, rollout is reversible, and support can prepare clear admin guidance.",
            "Top risks: SSO latency, unverified audit logs, and missing rollback messaging.",
            "Required owners: engineering for latency monitoring, security for audit-log signoff, support for FAQ and escalation macro.",
            "Next actions: confirm audit-log coverage, assign rollback owner, publish support docs, and monitor p95 auth latency during rollout.",
        ]
    ),
    "critic": "ACCEPT",
    "incident-responder": "Stabilize: pause rollout. Communicate: notify affected customers. Assign: name incident lead. Review: document root cause.",
}


if __name__ == "__main__":
    result = run(
        workflow,
        "Prepare the launch brief for a new enterprise SSO feature.",
        mock_responses=mock_responses,
    )
    configured = [
        classifier,
        product_reviewer,
        engineering_reviewer,
        security_reviewer,
        support_reviewer,
        brief_writer,
        critic,
        incident_responder,
    ]
    print(
        json.dumps(
            {
                "configuredAgents": [
                    {
                        "name": agent["name"],
                        "tools": agent.get("tools", []),
                        "instructionSections": len(agent["instructions"].split("\n\n")),
                    }
                    for agent in configured
                ],
                "workflow": "bind(classifier, { launch: iterate(pipe(map(reviewers), briefWriter), critic), incident })",
            },
            indent=2,
        )
    )
    print("\nresult:")
    print(result["text"])
    print(f"\nrounds: {result.get('rounds')} accepted? {result.get('accepted?')}")

// Non-trivial launch-readiness tutorial through the karcarthy JSON bridge.

import { execFileSync } from "node:child_process";

type Workflow = Record<string, unknown>;

function agent(name: string, instructions: string): Workflow {
  return { type: "agent", name, instructions };
}

function run(
  workflow: Workflow,
  input: string,
  adapter = "mock",
  mockResponses?: Record<string, string>,
): any {
  const req = JSON.stringify({
    workflow,
    input,
    adapter,
    ...(mockResponses ? { "mock-responses": mockResponses } : {}),
  });
  const out = execFileSync("clojure", ["-M", "-m", "karcarthy.cli"], {
    input: req,
    encoding: "utf8",
  });
  return JSON.parse(out);
}

const reviewers: Workflow = {
  type: "map",
  branches: [
    agent("product-reviewer", "Review launch value and user impact."),
    agent("engineering-reviewer", "Review operational risk and rollout plan."),
    agent("security-reviewer", "Review policy, data, and abuse risk."),
    agent("support-reviewer", "Review support readiness and messaging."),
  ],
};

const launchBrief: Workflow = {
  type: "iterate",
  worker: {
    type: "pipe",
    steps: [
      reviewers,
      agent("brief-writer", "Turn reviewer notes into a launch brief."),
    ],
  },
  evaluator: agent("critic", "Reply ACCEPT when the launch brief is complete."),
  "max-rounds": 2,
};

const workflow: Workflow = {
  type: "bind",
  source: agent("classifier", "Classify as launch or incident."),
  routes: {
    launch: launchBrief,
    incident: agent("incident-responder", "Write an incident response plan."),
  },
  default: launchBrief,
};

const mockResponses: Record<string, string> = {
  classifier: "launch",
  "product-reviewer": "Product: enterprise admins need this before rollout.",
  "engineering-reviewer": "Engineering: ship behind a flag and watch SSO latency.",
  "security-reviewer": "Security: no new sensitive data; retain audit logs.",
  "support-reviewer": "Support: publish setup docs and prepare rollback messaging.",
  "brief-writer": [
    "Decision: launch with a staged rollout.",
    "Risks: SSO latency, misconfigured tenants, support readiness.",
    "Next actions: owner signoff, feature flag, docs update, launch monitor.",
  ].join("\n"),
  critic: "ACCEPT",
  "incident-responder": "Incident plan: stabilize, communicate, then review.",
};

const result = run(
  workflow,
  "Prepare the launch brief for a new enterprise SSO feature.",
  "mock",
  mockResponses,
);

console.log(JSON.stringify({ workflow }, null, 2));
console.log("\nresult:");
console.log(result.text);
console.log(`\nrounds: ${result.rounds} accepted? ${result["accepted?"]}`);

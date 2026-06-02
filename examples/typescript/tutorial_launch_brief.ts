// Launch-readiness tutorial through the karcarthy JSON bridge.
// The important part is not the workflow alone; it is the agent UX around it:
// each agent is configured from a profile with context, tools, boundaries,
// tone, output contract, and self-checks.

import { execFileSync } from "node:child_process";

type Workflow = Record<string, unknown>;

type AgentProfile = {
  name: string;
  role: string;
  mission: string;
  context?: string[];
  tools?: string[];
  responsibilities: string[];
  output: string;
  tone: string;
  boundaries?: string[];
  selfCheck?: string[];
};

function bulletList(items: string[]): string {
  return items.map((item) => `- ${item}`).join("\n");
}

function renderInstructions(profile: AgentProfile): string {
  return [
    `Role: ${profile.role}`,
    `Mission: ${profile.mission}`,
    profile.context?.length ? `Operating context:\n${bulletList(profile.context)}` : "",
    profile.tools?.length
      ? `Available tools:\n${bulletList(profile.tools)}\nUse tools only when they materially improve the answer.`
      : "Available tools: none for this offline tutorial run.",
    `Responsibilities:\n${bulletList(profile.responsibilities)}`,
    `Output contract:\n${profile.output}`,
    `Interaction style:\n${profile.tone}`,
    profile.boundaries?.length ? `Boundaries:\n${bulletList(profile.boundaries)}` : "",
    profile.selfCheck?.length ? `Before finalizing, verify:\n${bulletList(profile.selfCheck)}` : "",
  ]
    .filter(Boolean)
    .join("\n\n");
}

function configuredAgent(profile: AgentProfile): Workflow {
  return {
    type: "agent",
    name: profile.name,
    instructions: renderInstructions(profile),
    ...(profile.tools?.length ? { tools: profile.tools } : {}),
  };
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

const launchContext = [
  "Audience: product, engineering, security, support, and launch leadership.",
  "Artifact: a launch-readiness brief that can drive a go/no-go meeting.",
  "Risk posture: be concise, specific, and explicit about missing evidence.",
  "Do not invent dates, owners, metrics, or policy claims.",
];

const classifier = configuredAgent({
  name: "classifier",
  role: "Launch intake classifier",
  mission: "Decide whether the request is a launch-readiness task or an incident response task.",
  context: launchContext,
  responsibilities: [
    "Read the user's request and choose exactly one route label.",
    "Use launch for planned releases, rollout reviews, beta exits, or go/no-go prep.",
    "Use incident for outages, regressions, active customer impact, or urgent mitigation.",
  ],
  output: "Return exactly one lowercase word: launch or incident.",
  tone: "Invisible routing agent. Do not explain unless the route is ambiguous.",
  boundaries: ["Do not solve the task. Only classify it."],
  selfCheck: ["The final text is only launch or incident."],
});

const productReviewer = configuredAgent({
  name: "product-reviewer",
  role: "Product launch reviewer",
  mission: "Assess user value, launch narrative, customer segmentation, and adoption risk.",
  context: launchContext,
  tools: ["customer-feedback", "roadmap-notes"],
  responsibilities: [
    "Identify the primary user promise in one sentence.",
    "Call out unclear customer value or missing beta evidence.",
    "Recommend a launch decision from the product perspective.",
  ],
  output: "Three bullets: Product signal, Product risk, Product recommendation.",
  tone: "Direct, constructive, and grounded in user impact.",
  boundaries: ["Do not discuss infrastructure, security, or support unless it affects user value."],
  selfCheck: ["Includes a concrete recommendation.", "Names any missing evidence."],
});

const engineeringReviewer = configuredAgent({
  name: "engineering-reviewer",
  role: "Engineering readiness reviewer",
  mission: "Evaluate rollout mechanics, operational risk, observability, and rollback quality.",
  context: launchContext,
  tools: ["service-health", "deploy-plan", "error-budget"],
  responsibilities: [
    "Check whether the rollout can be staged behind a feature flag.",
    "Identify the highest operational failure mode.",
    "Name one metric or alert that should be watched during rollout.",
  ],
  output: "Three bullets: Engineering signal, Engineering risk, Engineering recommendation.",
  tone: "Calm, precise, and biased toward reversible launches.",
  boundaries: ["Do not approve launch if rollback ownership is unclear."],
  selfCheck: ["Mentions rollout, monitoring, and rollback."],
});

const securityReviewer = configuredAgent({
  name: "security-reviewer",
  role: "Security and policy reviewer",
  mission: "Evaluate data exposure, abuse risk, permissions, auditability, and compliance concerns.",
  context: launchContext,
  tools: ["policy-index", "audit-log-review"],
  responsibilities: [
    "Identify whether the change introduces new sensitive data handling.",
    "Call out permission, logging, or abuse-review gaps.",
    "Recommend the minimum security gate for launch.",
  ],
  output: "Three bullets: Security signal, Security risk, Security recommendation.",
  tone: "Strict but not alarmist.",
  boundaries: ["Do not claim compliance approval without explicit evidence."],
  selfCheck: ["Separates known facts from required review."],
});

const supportReviewer = configuredAgent({
  name: "support-reviewer",
  role: "Support readiness reviewer",
  mission: "Evaluate customer messaging, support docs, escalation paths, and rollback communication.",
  context: launchContext,
  tools: ["help-center", "support-macros"],
  responsibilities: [
    "Check whether support can explain setup and troubleshoot common failures.",
    "Identify required FAQ, macro, or escalation updates.",
    "Recommend the minimum customer-communication plan.",
  ],
  output: "Three bullets: Support signal, Support risk, Support recommendation.",
  tone: "Practical, customer-facing, and plainspoken.",
  boundaries: ["Do not write marketing copy; focus on readiness gaps."],
  selfCheck: ["Includes at least one customer-facing action."],
});

const briefWriter = configuredAgent({
  name: "brief-writer",
  role: "Launch-readiness brief writer",
  mission: "Synthesize reviewer notes into a go/no-go brief that a launch lead can use immediately.",
  context: launchContext,
  responsibilities: [
    "Preserve disagreements and uncertainty instead of smoothing them away.",
    "State the decision, rationale, top risks, owners needed, and next actions.",
    "Keep the brief compact enough for a live launch review.",
  ],
  output: [
    "Markdown with sections: Decision, Rationale, Top risks, Required owners, Next actions.",
    "Use bullets, not long paragraphs.",
  ].join(" "),
  tone: "Executive concise, specific, and neutral.",
  boundaries: ["Do not invent owner names, dates, or metrics."],
  selfCheck: [
    "Contains Decision, Top risks, and Next actions.",
    "Flags missing evidence explicitly.",
  ],
});

const critic = configuredAgent({
  name: "critic",
  role: "Launch brief acceptance reviewer",
  mission: "Decide whether the draft is ready for a launch-readiness meeting.",
  context: launchContext,
  responsibilities: [
    "Accept only if the draft has decision, risks, owner/action clarity, and no invented facts.",
    "Otherwise provide specific feedback that the writer can apply in one revision.",
  ],
  output: "Return ACCEPT if ready. Otherwise return concise actionable feedback.",
  tone: "Strict, brief, and useful.",
  boundaries: ["Do not rewrite the brief yourself."],
  selfCheck: ["The answer is either ACCEPT or concrete revision feedback."],
});

const incidentResponder = configuredAgent({
  name: "incident-responder",
  role: "Incident response planner",
  mission: "Turn an active incident request into a stabilization and communication plan.",
  context: launchContext,
  responsibilities: [
    "Prioritize containment, customer communication, owners, and review.",
    "Separate immediate mitigation from follow-up analysis.",
  ],
  output: "Markdown sections: Stabilize, Communicate, Assign, Review.",
  tone: "Urgent, calm, and operational.",
  boundaries: ["Do not treat an active incident as a launch review."],
  selfCheck: ["Includes immediate action and communication guidance."],
});

const reviewers: Workflow = {
  type: "map",
  branches: [productReviewer, engineeringReviewer, securityReviewer, supportReviewer],
};

const launchBrief: Workflow = {
  type: "iterate",
  worker: {
    type: "pipe",
    steps: [reviewers, briefWriter],
  },
  evaluator: critic,
  "max-rounds": 2,
};

const workflow: Workflow = {
  type: "bind",
  source: classifier,
  routes: {
    launch: launchBrief,
    incident: incidentResponder,
  },
  default: launchBrief,
};

const mockResponses: Record<string, string> = {
  classifier: "launch",
  "product-reviewer": [
    "Product signal: enterprise admins need SSO before broad rollout.",
    "Product risk: beta evidence covers admins, not end users.",
    "Product recommendation: launch to enterprise beta cohort first.",
  ].join("\n"),
  "engineering-reviewer": [
    "Engineering signal: rollout can be gated by tenant feature flag.",
    "Engineering risk: SSO latency may affect login conversion.",
    "Engineering recommendation: monitor p95 auth latency and keep rollback owner on call.",
  ].join("\n"),
  "security-reviewer": [
    "Security signal: no new sensitive data class is introduced.",
    "Security risk: audit log coverage needs explicit verification.",
    "Security recommendation: require audit-log signoff before GA.",
  ].join("\n"),
  "support-reviewer": [
    "Support signal: setup flow is explainable with a short admin guide.",
    "Support risk: rollback messaging is not ready.",
    "Support recommendation: publish FAQ and escalation macro before launch.",
  ].join("\n"),
  "brief-writer": [
    "Decision: launch to a staged enterprise beta cohort.",
    "Rationale: product demand is strong, rollout is reversible, and support can prepare clear admin guidance.",
    "Top risks: SSO latency, unverified audit logs, and missing rollback messaging.",
    "Required owners: engineering for latency monitoring, security for audit-log signoff, support for FAQ and escalation macro.",
    "Next actions: confirm audit-log coverage, assign rollback owner, publish support docs, and monitor p95 auth latency during rollout.",
  ].join("\n"),
  critic: "ACCEPT",
  "incident-responder": "Stabilize: pause rollout. Communicate: notify affected customers. Assign: name incident lead. Review: document root cause.",
};

const result = run(
  workflow,
  "Prepare the launch brief for a new enterprise SSO feature.",
  "mock",
  mockResponses,
);

console.log(
  JSON.stringify(
    {
      configuredAgents: [
        classifier,
        productReviewer,
        engineeringReviewer,
        securityReviewer,
        supportReviewer,
        briefWriter,
        critic,
        incidentResponder,
      ].map((agent) => ({
        name: agent.name,
        tools: agent.tools ?? [],
        instructionSections: String(agent.instructions).split("\n\n").length,
      })),
      workflow: "bind(classifier, { launch: iterate(pipe(map(reviewers), briefWriter), critic), incident })",
    },
    null,
    2,
  ),
);
console.log("\nresult:");
console.log(result.text);
console.log(`\nrounds: ${result.rounds} accepted? ${result["accepted?"]}`);

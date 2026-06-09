// Claude dynamic-agent style orchestration through karcarthy's JSON bridge.
//
// Run offline:
//   node examples/javascript/claude_dynamic_agents.mjs
//
// Print the workflow JSON without invoking Clojure:
//   node examples/javascript/claude_dynamic_agents.mjs --print
//
// Run live through Claude CLI, if Java, Clojure, and Claude are installed:
//   KARCARTHY_CLAUDE_LIVE=1 node examples/javascript/claude_dynamic_agents.mjs

import {
  agent,
  delegate,
  pipe,
  reduce,
  revise,
  runKarcarthy,
} from "./karcarthy-json.mjs";

const context = [
  "System: a payments company is migrating card-token vaulting from a legacy service to a new regionalized platform.",
  "Constraints: PCI scope must not expand; regional data residency must be preserved; checkout p95 cannot regress by more than 30 ms; rollback must be reversible per tenant.",
  "Known risks: token replay semantics differ, old analytics jobs read from the vault directly, and support needs migration-visible error codes.",
  "Audience: staff engineers, security, compliance, support, and the launch owner.",
];

function bullets(items) {
  return items.map((item) => `- ${item}`).join("\n");
}

function instructions(profile) {
  return [
    `Role: ${profile.role}`,
    `Mission: ${profile.mission}`,
    `Operating context:\n${bullets(context)}`,
    profile.tools?.length
      ? [
          `Tool allowlist:\n${bullets(profile.tools)}`,
          "These names must already exist in the selected CLI, SDK, or MCP setup.",
          "The mock runner ignores tool names.",
        ].join("\n")
      : "Tool allowlist: none.",
    `Responsibilities:\n${bullets(profile.responsibilities)}`,
    `Output contract:\n${profile.output}`,
    profile.boundaries?.length ? `Boundaries:\n${bullets(profile.boundaries)}` : "",
    profile.selfCheck?.length ? `Before finalizing, verify:\n${bullets(profile.selfCheck)}` : "",
  ]
    .filter(Boolean)
    .join("\n\n");
}

function configured(profile) {
  return agent({
    name: profile.name,
    tools: profile.tools,
    instructions: instructions(profile),
  });
}

const planner = configured({
  name: "workstream-planner",
  role: "Claude dynamic workflow planner",
  mission: "Split the migration review into independent workstreams that can run in parallel.",
  responsibilities: [
    "Choose workstreams with minimal shared context.",
    "Separate architecture, security, operations, analytics, and support risk.",
    "Keep each workstream narrow enough for one focused agent pass.",
  ],
  output: 'Reply with EDN only: {:subtasks ["..." "..." ...]}. Use exactly five subtasks.',
  boundaries: ["Do not solve the subtasks.", "Do not include prose outside the EDN map."],
  selfCheck: ["Every subtask can be delegated independently."],
});

const evidenceScout = configured({
  name: "evidence-scout",
  role: "Migration evidence scout",
  mission: "Collect the facts, assumptions, and missing evidence for one workstream.",
  tools: ["Read", "Grep", "Glob"],
  responsibilities: [
    "Extract facts from the mission and workstream.",
    "Name missing measurements, signoffs, or owner decisions.",
    "Preserve uncertainty instead of converting it into approval.",
  ],
  output: "Markdown bullets: Known evidence, Missing evidence, Decision pressure.",
  boundaries: ["No invented facts.", "No launch recommendation yet."],
  selfCheck: ["Known facts and assumptions are separated."],
});

const riskAnalyst = configured({
  name: "risk-analyst",
  role: "Blast-radius analyst",
  mission: "Turn the evidence packet into concrete production risk.",
  responsibilities: [
    "Rank user-visible, operational, security, and compliance failure modes.",
    "Attach each high-risk item to a containment or rollback control.",
    "Call out risks that need a rehearsal or explicit signoff.",
  ],
  output: "Markdown table: Risk, Why it matters, Likelihood, Impact, Control.",
  boundaries: ["Do not approve launch.", "Do not hide missing evidence."],
  selfCheck: ["Every high-risk row has a control."],
});

const verifier = configured({
  name: "verifier",
  role: "Independent workstream verifier",
  mission: "Challenge the analysis before synthesis.",
  responsibilities: [
    "Reject unsupported claims.",
    "Flag missing tests, monitors, rollback gates, and communication gaps.",
    "Preserve dissent for the final memo.",
  ],
  output: "Markdown bullets: Accept, Challenge, Required follow-up.",
  boundaries: ["Do not write the final memo."],
  selfCheck: ["Follow-up items are actionable in one review meeting."],
});

const integrator = configured({
  name: "integrator",
  role: "Dynamic-agent synthesis lead",
  mission: "Merge verified workstreams into a production-readiness memo.",
  responsibilities: [
    "State a go/no-go recommendation.",
    "Preserve disagreements and unknowns.",
    "Produce gates and rollback controls that the launch owner can execute.",
  ],
  output: "Markdown sections: Recommendation, Evidence map, Blocking gaps, Rollback plan, Review agenda.",
  boundaries: ["Do not invent owners, dates, or measured results."],
  selfCheck: ["The memo remains useful if the answer is no-go."],
});

const critic = configured({
  name: "critic",
  role: "Acceptance critic",
  mission: "Decide whether the memo is ready for a migration review.",
  responsibilities: [
    "Accept only if the memo has recommendation, evidence map, blocking gaps, and rollback plan.",
    "Reject if the memo invents facts or smooths over missing evidence.",
  ],
  output: 'Reply with EDN only: {:accept? true} or {:accept? false :feedback "specific revision instructions"}.',
  boundaries: ["Do not rewrite the memo yourself."],
  selfCheck: ["The final output is exactly one EDN map."],
});

const worker = pipe(evidenceScout, riskAnalyst, verifier);
const dynamicTeam = reduce(delegate(planner, worker, 5), integrator);
const workflow = revise(dynamicTeam, critic, 2);

const prompt = "Prepare a migration-readiness memo for the card-token vault migration.";

const mockResponses = {
  "workstream-planner": '{:subtasks ["Architecture and token semantics" "PCI scope and data residency" "Checkout latency and rollback" "Analytics dependencies" "Support playbooks and customer messaging"]}',
  "evidence-scout": [
    "Known evidence: tenant feature flags exist; checkout p95 has a 30 ms regression budget.",
    "Missing evidence: measured latency, compliance signoff, analytics dependency inventory, rollback owner.",
    "Decision pressure: unresolved evidence blocks broad GA.",
  ].join("\n"),
  "risk-analyst": [
    "| Risk | Why it matters | Likelihood | Impact | Control |",
    "| --- | --- | --- | --- | --- |",
    "| Token semantics mismatch | Could break checkout or analytics assumptions | Medium | High | Compatibility tests and dual-read rehearsal |",
    "| Rollback ambiguity | Tenant rollback may not reverse side effects | Medium | High | Named owner and rollback drill |",
  ].join("\n"),
  verifier: [
    "Accept: risks are concrete and tied to launch controls.",
    "Challenge: latency and compliance evidence are still missing.",
    "Required follow-up: attach measurements, signoffs, dependency inventory, and rollback rehearsal notes.",
  ].join("\n"),
  integrator: [
    "## Recommendation",
    "No-go for broad GA; proceed only with a tenant-gated beta after blocking gaps close.",
    "",
    "## Evidence map",
    "- Workstreams covered architecture, PCI/residency, latency/rollback, analytics, and support.",
    "",
    "## Blocking gaps",
    "- Measured p95 checkout latency.",
    "- Compliance signoff for PCI and residency boundaries.",
    "- Inventory of analytics jobs reading the old vault directly.",
    "- Named rollback owner and rehearsal evidence.",
    "",
    "## Rollback plan",
    "- Keep rollout per tenant, preserve old-token compatibility, rehearse rollback, and monitor checkout p95.",
    "",
    "## Review agenda",
    "- Measurements, signoffs, dependency inventory, support readiness, and launch decision.",
  ].join("\n"),
  critic: "{:accept? true}",
};

function stripTools(node) {
  if (Array.isArray(node)) return node.map(stripTools);
  if (!node || typeof node !== "object") return node;
  const next = Object.fromEntries(Object.entries(node).map(([key, value]) => [key, stripTools(value)]));
  if (next.type === "agent") delete next.tools;
  return next;
}

if (process.argv.includes("--print")) {
  console.log(JSON.stringify({ workflow, prompt }, null, 2));
} else {
  const live = Boolean(process.env.KARCARTHY_CLAUDE_LIVE);
  const runnableWorkflow = live ? stripTools(workflow) : workflow;
  let result;
  try {
    result = runKarcarthy(runnableWorkflow, prompt, {
      runner: live ? "claude" : "mock",
      mockResponses: live ? undefined : mockResponses,
    });
  } catch (error) {
    console.error("Could not run karcarthy through the JSON bridge.");
    console.error("This example needs a working JDK plus the Clojure CLI, because bin/karcarthy runs on the JVM.");
    console.error(error.stderr?.toString()?.trim() || error.message);
    process.exitCode = 1;
    process.exit();
  }

  console.log(
    JSON.stringify(
      {
        shape: "plan workstreams -> parallel worker pipeline -> synthesize -> critique",
        live,
        maxConcurrency: 5,
      },
      null,
      2,
    ),
  );
  console.log("\nresult:");
  console.log(result.text);
  console.log(`\nrounds: ${result.rounds} accepted? ${result["accepted?"]}`);
}

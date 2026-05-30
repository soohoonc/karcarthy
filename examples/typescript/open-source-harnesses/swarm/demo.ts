// OpenAI Swarm-style triage handoff through the karcarthy JSON bridge.

import { execFileSync } from "node:child_process";

type Workflow = Record<string, unknown>;

function agent(name: string, instructions: string): Workflow {
  return { type: "agent", name, instructions };
}

function run(workflow: Workflow, input: string): any {
  const req = JSON.stringify({ workflow, input, runner: "mock" });
  const out = execFileSync("clojure", ["-M", "-m", "karcarthy.cli"], {
    input: req,
    encoding: "utf8",
  });
  return JSON.parse(out);
}

const workflow: Workflow = {
  type: "route",
  router: agent("triage", "Classify as refund, sales, or support."),
  routes: {
    refund: agent("refund", "Handle refunds."),
    sales: agent("sales", "Handle sales questions."),
    support: agent("support", "Handle general support."),
  },
  default: agent("support", "Handle general support."),
};

console.log("workflow:");
console.log(JSON.stringify(workflow, null, 2));
console.log("\nresult:");
console.log(run(workflow, "I was charged twice and need a refund.").text);

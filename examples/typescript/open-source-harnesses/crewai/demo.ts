// CrewAI-style sequential crew through the karcarthy JSON bridge.

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
  type: "chain",
  steps: [
    agent("researcher/market", "Find the market signal."),
    agent("analyst/risk", "Name the main risk."),
    agent("writer/brief", "Write the final brief."),
  ],
};

console.log("workflow:");
console.log(JSON.stringify(workflow, null, 2));
console.log("\nresult:");
console.log(run(workflow, "Show why karcarthy is useful.").text);

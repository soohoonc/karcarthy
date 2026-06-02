// CrewAI-style sequential crew through the karcarthy executable.

import { run, type Workflow } from "../karcarthy";

function agent(name: string, instructions: string): Workflow {
  return { type: "agent", name, instructions };
}

const workflow: Workflow = {
  type: "pipe",
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

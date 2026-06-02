// AutoGen-style round-robin group chat through the karcarthy executable.

import { run, type Workflow } from "../karcarthy";

function agent(name: string, instructions: string): Workflow {
  return { type: "agent", name, instructions };
}

const workflow: Workflow = {
  type: "pipe",
  steps: [
    agent("planner", "Plan the example."),
    agent("builder", "Build the example."),
    agent("reviewer", "Review the example."),
  ],
};

console.log("workflow:");
console.log(JSON.stringify(workflow, null, 2));
console.log("\nresult:");
console.log(run(workflow, "Plan a tiny orchestration demo.").text);

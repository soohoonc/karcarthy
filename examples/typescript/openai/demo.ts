// OpenAI Agents SDK-style specialist routing through the karcarthy executable.

import { run, type Workflow } from "../karcarthy";

function agent(name: string, instructions: string): Workflow {
  return { type: "agent", name, instructions };
}

const workflow: Workflow = {
  type: "bind",
  source: agent("triage", "Classify as refund, sales, or support."),
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
console.log(
  run(workflow, "I was charged twice and need a refund.", "mock", {
    triage: "refund",
    refund: "Refund specialist: verify charge id, explain policy, and start reversal.",
    sales: "Sales specialist: answer pricing and plan-fit questions.",
    support: "Support specialist: gather context and unblock the customer.",
  }).text,
);

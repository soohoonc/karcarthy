// Drive karcarthy from TypeScript.
//
// karcarthy is a Clojure library, but a workflow is just data, so any language can
// build one and run it. This builds workflows as plain objects and runs them
// through the karcarthy executable. The workflow crosses the boundary as data,
// so an agent can generate or edit a workflow the same way.
//
// Run from the repo root (any of):
//   npx tsx examples/typescript/demo.ts
//   bun run examples/typescript/demo.ts
//   ts-node examples/typescript/demo.ts

import { run, type Workflow } from "./karcarthy";

// 1) A workflow is data: a pipe of two agents, run on the offline mock runner.
const workflow: Workflow = {
  type: "pipe",
  steps: [
    { type: "agent", name: "researcher", instructions: "Research the question." },
    { type: "agent", name: "summarizer", instructions: "Summarize in one line." },
  ],
};
console.log("pipe ->", run(workflow, "what is a monad?").text);

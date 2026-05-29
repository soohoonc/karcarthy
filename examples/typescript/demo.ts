// Drive karcarthy from TypeScript.
//
// karcarthy is a Clojure library, but a flow is just data, so any language can
// build one and run it. This builds flows as plain objects, sends them to the
// karcarthy CLI bridge (`clojure -M -m karcarthy.cli`) as JSON, and reads the
// JSON result back. The workflow crosses the boundary as data, so an agent can
// author or edit a flow the same way.
//
// Run from the repo root (any of):
//   npx tsx examples/typescript/demo.ts
//   bun run examples/typescript/demo.ts
//   ts-node examples/typescript/demo.ts
// Add --live to also run the self-editing agent (real claude calls).

import { execFileSync } from "node:child_process";

type Flow = Record<string, unknown>;

function run(flow: Flow, input: string, harness = "mock"): any {
  const req = JSON.stringify({ flow, input, harness });
  const out = execFileSync("clojure", ["-M", "-m", "karcarthy.cli"], {
    input: req,
    encoding: "utf8",
  });
  return JSON.parse(out);
}

// 1) A flow is data: a chain of two agents, run on the offline mock harness.
const chain: Flow = {
  type: "chain",
  steps: [
    { type: "agent", name: "researcher", instructions: "Research the question." },
    { type: "agent", name: "summarizer", instructions: "Summarize in one line." },
  ],
};
console.log("chain ->", run(chain, "what is a monad?").text);

// 2) An agent edits its own definition at runtime, across the bridge (real model).
if (process.argv.includes("--live")) {
  const evolve: Flow = {
    type: "evolve",
    agent: { type: "agent", name: "poet", instructions: "You are a mediocre poet." },
    "max-rounds": 3,
  };
  const res = run(
    evolve,
    "Improve yourself into an expert, then write one line about Lisp.",
    "claude",
  );
  console.log("evolve rounds:", res.rounds, "->", res.text);
}

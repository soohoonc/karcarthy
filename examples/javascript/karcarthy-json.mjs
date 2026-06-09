import { execFileSync } from "node:child_process";
import { existsSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

export const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../..");

function command() {
  if (process.env.KARCARTHY_BIN) {
    return { file: process.env.KARCARTHY_BIN, args: ["json"] };
  }

  const launcher = resolve(repoRoot, "bin/karcarthy");
  if (existsSync(launcher)) {
    return { file: launcher, args: ["json"] };
  }

  return { file: "clojure", args: ["-M", "-m", "karcarthy.cli", "json"] };
}

export function runKarcarthy(workflow, input, options = {}) {
  const { adapter = "mock", mockResponses } = options;
  const { file, args } = command();
  const request = {
    workflow,
    input,
    adapter,
    ...(mockResponses ? { "mock-responses": mockResponses } : {}),
  };

  const out = execFileSync(file, args, {
    input: JSON.stringify(request),
    encoding: "utf8",
    cwd: repoRoot,
  });

  return JSON.parse(out);
}

export function agent(profile) {
  return {
    type: "agent",
    name: profile.name,
    instructions: profile.instructions,
    ...(profile.model ? { model: profile.model } : {}),
    ...(profile.tools?.length ? { tools: profile.tools } : {}),
    ...(profile.adapter ? { adapter: profile.adapter } : {}),
  };
}

export function pipe(...steps) {
  return { type: "pipe", steps };
}

export function branch(branches, maxConcurrency) {
  return {
    type: "branch",
    branches,
    ...(maxConcurrency ? { "max-concurrency": maxConcurrency } : {}),
  };
}

export function delegate(planner, worker, maxConcurrency) {
  return {
    type: "delegate",
    planner,
    worker,
    ...(maxConcurrency ? { "max-concurrency": maxConcurrency } : {}),
  };
}

export function reduce(source, reducer) {
  return { type: "reduce", source, reducer };
}

export function revise(worker, evaluator, maxRounds = 2) {
  return { type: "revise", worker, evaluator, "max-rounds": maxRounds };
}

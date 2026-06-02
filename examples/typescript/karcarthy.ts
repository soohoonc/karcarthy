import { execFileSync } from "node:child_process";
import { existsSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

export type Workflow = Record<string, unknown>;

type Command = {
  file: string;
  args: string[];
};

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), "../..");

function command(): Command {
  const override = process.env.KARCARTHY_BIN;
  if (override) {
    return { file: override, args: [] };
  }

  const launcher = resolve(repoRoot, "bin/karcarthy");
  if (existsSync(launcher)) {
    return { file: launcher, args: [] };
  }

  return { file: "clojure", args: ["-M", "-m", "karcarthy.cli"] };
}

export function run(
  workflow: Workflow,
  input: string,
  adapter = "mock",
  mockResponses?: Record<string, string>,
): any {
  const { file, args } = command();
  const request = JSON.stringify({
    workflow,
    input,
    adapter,
    ...(mockResponses ? { "mock-responses": mockResponses } : {}),
  });
  const out = execFileSync(file, args, {
    input: request,
    encoding: "utf8",
    cwd: repoRoot,
  });
  return JSON.parse(out);
}

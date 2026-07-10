# Security Policy

Report vulnerabilities privately through GitHub's security advisory mechanism
for this repository. Do not open a public issue for an undisclosed
vulnerability. Include reproduction steps and the affected commit.

## Generated Clojure is executable code

karcarthy intentionally evaluates model-authored Clojure. `read-agent-form`
binds `*read-eval*` to false so the reader cannot execute `#=` forms, but
`eval-agent-form!` later evaluates the checked form as JVM Clojure. In the
default full-trust mode it can access everything available to its evaluation
namespace and process.

Treat generated source as trusted code. Giving a model zero-arity `(agent)` in
its tool set grants this evaluation capability; calling `compile-agent!` from a
custom body does the same. Do neither when the process contains capabilities
the source must not exercise. Applications needing isolation should place the
entire harness in a process, container, VM, restricted classloader, or a future
alternate evaluation policy. Do not mistake macroexpansion or contracts for a
security sandbox.

## Tools and context

Tools execute application Clojure with access to the local Runtime context.
Use narrow context values, input/output contracts, `:enabled?`, guardrails,
approval, timeouts, and least-privilege credentials. Approval is currently a
synchronous allow/deny decision; durable suspension is not implemented.

Tool output and event payloads may contain sensitive data. Redact them in the
observer before external export.

The workspace `read`, `write`, `edit`, and `search` tools reject paths
that resolve outside their configured workspace, including existing symlink
ancestors. This is a correctness boundary for those tools, not a process
sandbox. `bash` starts in the workspace but can access anything allowed to the
karcarthy process. Run untrusted coding tasks inside an OS sandbox or VM.

## MCP and ACP

An stdio MCP server is an executable child process with the same OS authority
as karcarthy. Treat its command, arguments, environment, and implementation as
trusted code. Tool approval occurs after the server has started; it controls a
model-requested MCP tool call, not process creation. ACP is currently a stdio
boundary intended for a trusted local client. An ACP client may supply stdio
MCP server configurations for a session, so do not expose the server's stdio
transport to an untrusted relay.

Unknown MCP tools require approval by default. Setting `:approval :never`
means the server and all adapted tools have been explicitly trusted.

## Model providers

The OpenAI transport sends instructions, pending model input, tool schemas, and
tool outputs to the configured Responses API endpoint. Keep `OPENAI_API_KEY`
out of source control, review provider data handling, and avoid placing secrets
in prompts or tool results.

## Denial of service

Always configure appropriate model-call, token, generated-form, Agent-depth,
parallelism, cancellation, and deadline limits for recursively generated code.

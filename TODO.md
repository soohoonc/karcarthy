# TODO

Concrete implementation work, mostly follow-ups from the 2026-07-03 ACP
spec-compliance audits against <https://agentclientprotocol.com> (fixes for
the confirmed spec issues landed with the audit; these are the remaining
functional gaps and robustness edges — none violate a client MUST). Direction
and non-goals live in [ROADMAP.md](ROADMAP.md).

## ACP runner (`src/karcarthy/runner/acp.clj`)

### Protocol features not yet implemented

- **Authentication.** Support `authenticate` using one of the methods
  advertised in the `initialize` response. Today an agent that requires auth
  answers `session/new` with an `auth_required` error, which surfaces as a
  failed run — a functional gap, not a spec violation. (The spec's only hard
  MUST here — never call `logout` unless advertised — is satisfied because the
  runner never calls it.)
- **Cancellation.** Send `session/cancel` on timeout instead of
  `.destroyForcibly` in `read-line!`, then answer any pending
  `session/request_permission` with the `cancelled` outcome and wait for the
  prompt response with `stopReason "cancelled"`. Today a timeout force-kills
  the agent process, which also leaves a reused `connect!` connection dead.
- **Session close on reused connections.** On a long-lived `connect!`
  connection every run creates a session that is never closed, so sessions
  accumulate agent-side for the life of the process. Call `session/close`
  (capability-gated) after the prompt turn when the agent advertises it.
- **Terminals.** Implement the `terminal/*` client methods so callers can
  advertise `clientCapabilities.terminal`. Today advertising it yields
  `-32601` when the agent calls a terminal method.
- **Rich prompt content.** Allow image / audio / embedded-context content
  blocks in `session/prompt`, gated on the agent's `promptCapabilities`.
  Today the runner only ever sends text blocks (the mandatory baseline).

### Robustness (spec-legal, but sharp edges)

- **`_meta` key namespace is dropped on the wire.** `prompt-params` attaches
  `:_meta {:karcarthy.dev/agent {…}}`, but `json/write-str`'s default key-fn
  is `name`, so the wire payload says `"agent"`, not `"karcarthy.dev/agent"`.
  Serialize with a key-fn that preserves namespaces so the extension key
  follows ACP extensibility conventions.
- **`permission-outcome` NPEs on an option without `:kind`.**
  `str/starts-with?` throws on nil, and the catch-all answers the permission
  request with `-32603` instead of an outcome. Guard nil kinds.
- **Malformed agent stdout desynchronizes reused connections.** A non-JSON
  line makes `read-message!` throw; the run fails fine, but a caller-supplied
  `:connection` is left mid-conversation with no marker that it is unusable.
  Consider marking the connection dead (and/or answering `-32700`).
- **`fs/write_text_file` fails when the parent directory is missing.**
  `spit` throws and the agent sees an opaque `-32603`. The spec is silent;
  creating parent directories is the friendlier reading.
- **`sessionId` is never validated.** Incoming fs requests, permission
  requests, and `session/update` notifications are attributed to the current
  run without checking `params.sessionId`. Fine while the client runs one
  turn at a time per connection; wrong if runs ever interleave.
- **`set-config!` matches every option against the initial list.** Each
  `session/set_config_option` response returns the refreshed `configOptions`;
  use it for subsequent matches, and consider validating values against the
  option's declared `options` before sending.
- **Permission fallback outcome.** When no `reject_*` option exists the
  runner answers `cancelled`, which the spec ties to `session/cancel`. There
  is no generic deny outcome in the protocol today; revisit if one is added.

### Testing

- **Live ACP conformance test** behind `KARCARTHY_LIVE` against a real ACP
  agent (e.g. `claude-code-acp`), mirroring the Claude runner's live test —
  the offline `sh`-scripted agents cannot catch wire-format drift.

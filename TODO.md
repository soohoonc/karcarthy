# TODO

Follow-ups from the ACP spec-compliance audits against
<https://agentclientprotocol.com>. Confirmed implementation issues have been
fixed; this file retains the one protocol-defined ambiguity worth watching and
the live-test invocation. Direction and non-goals live in
[ROADMAP.md](ROADMAP.md).

## ACP runner (`src/karcarthy/runner/acp.clj`)

### Protocol watch item

- **Permission fallback outcome.** When no `reject_*` option exists the
  runner answers `cancelled`, which the spec ties to `session/cancel`. There
  is no generic deny outcome in the protocol today; revisit if one is added.

### Testing live ACP

The opt-in conformance test runs when `KARCARTHY_LIVE` is set and
`KARCARTHY_ACP_COMMAND` contains an EDN argv vector such as
`["claude-code-acp"]`. It remains opt-in so the normal suite is offline and
free.

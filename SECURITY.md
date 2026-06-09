# Security Policy

## Supported versions

karcarthy is pre-1.0; the latest commit on the default branch is supported.

## Reporting a vulnerability

Please report security issues privately to **soohoon@greptile.com** rather than
opening a public issue. Include steps to reproduce and the affected version or
commit. We'll acknowledge the report and work with you on a fix and disclosure.

Some runners execute external programs by design. `process-runner` runs an argv
vector, `shell-runner` runs a shell command string, and
`openai-agents-runner` shells out to `python3`, so treat agent instructions,
tool allowlists, and runner commands as trusted input.

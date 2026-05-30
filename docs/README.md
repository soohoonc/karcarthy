# karcarthy docs

This directory captures the production configuration direction for karcarthy.
The top-level project docs still describe what exists today; these docs describe
the intended data model for a more idiomatic, protocol-aligned configuration
system.

## Documents

- [Agent configuration vocabulary](agent-configuration.md) defines the proposed
  resource kinds, operations, context model, integrations model, and graph
  shapes.
- [References](references/) records the protocol and SDK prior art behind the
  vocabulary decisions.

## Status

This is design documentation. The current experimental dynamic runtime in
`karcarthy.dynamic` still uses early operation names such as `:define-agent`,
`:patch-agent`, and `:answer`. The vocabulary in these docs is the target naming
pass for the next iteration.

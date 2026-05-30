# karcarthy docs

This directory captures the self-evolving runtime direction for karcarthy.
The top-level project docs still describe what exists today; these docs describe
the intended data model for a living agent runtime whose state can change during
execution.

## Documents

- [Self-evolving runtime vocabulary](agent-configuration.md) defines the
  proposed resource kinds, operations, context model, integrations model,
  environments, and graph shapes.
- [References](references/) records the protocol and SDK prior art behind the
  vocabulary decisions.

## Status

This is design documentation plus the first implementation pass. The experimental
dynamic runtime in `karcarthy.dynamic` accepts the living operation names
(`:put`, `:patch`, `:remove`, `:call`, `:emit`, `:return`, `:complete`) while
keeping early names such as `:define-agent`, `:patch-agent`, and `:answer` as
compatibility aliases.

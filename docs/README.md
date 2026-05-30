# karcarthy docs

This directory captures the default runtime direction for karcarthy. The
top-level project docs still describe what exists today; these docs describe how
agents, workflows, and history become mutable runtime data.

## Documents

- [Runtime vocabulary](agent-configuration.md) defines the proposed state shape
  and small operation set.
- [Orchestrator emulations](orchestrator-emulations.md) maps popular
  orchestrator shapes to karcarthy workflow data.
- [References](references/) records the protocol and SDK prior art behind the
  vocabulary decisions.

## Status

This is design documentation plus the first implementation pass. The current
code lives in `karcarthy.dynamic`, but the concept is intended to become the
default runtime model rather than a separate mode.

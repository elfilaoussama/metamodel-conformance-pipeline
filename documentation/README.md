# Documentation

Experiment Workspace is the web interface to the Metamodel Conformance Pipeline.
It supports source acquisition, bounded analysis, evidence inspection and formal
replay. The pipeline studies correspondence between source observations and
explicit theoretical conditions. It does not certify arbitrary source code.

## Learn by doing

Start with [Your first analysis](tutorials/first-analysis.md). It uses one small
Java file and explains the output before introducing repository-scale work.

## Complete a task

- [Find and acquire repositories](guides/github-sources.md): advanced GitHub
  search, selections, revision pinning and submission.
- [Interpret results](guides/interpret-results.md): decisions, denominators,
  witnesses, evidence and source navigation.
- [Replay and reproduce](guides/reproduce.md): distinguish artifact verification
  from fresh source observation.

## Understand the method

- [Pipeline architecture](explanation/architecture.md): responsibilities,
  contracts, execution boundaries and an architecture diagram.
- [Scientific methodology](explanation/methodology.md): measurement validity,
  independent controls and empirical study design.
- [Replaceable rule profiles](explanation/rule-profiles.md): formalization,
  evidence requirements and safe extensions.
- [Interface design rationale](explanation/interface-design.md): scientific
  workbench precedents, accessibility guidance and design decisions.

## Look up a detail

- [Configuration and troubleshooting](reference/operations.md).
- [Capabilities and limitations](reference/capabilities.md).
- [Web API](reference/web-api.md): endpoint contracts and recorded attempt fields.
- [Source and documentation map](reference/source-map.md).

## Interpret the scope correctly

This manual describes the current local interface. The engine has additional
command-line and provider capabilities. Read their contracts before using them.
An operation that is not available in the interface is not implied by its presence
in an architecture diagram. A successful job can contain unknown decisions or
counterexamples. Neither a clean dashboard nor formal replay establishes source
fidelity on its own.

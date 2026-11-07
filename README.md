# Metamodel Conformance Pipeline

A research instrument for comparing source-code observations with explicit formal
conditions. Language observers record evidence, a shared model preserves it, and
Alloy evaluates a versioned rule profile. The web interface is **Experiment
Workspace**.

## Start here

- [Documentation](documentation/README.md): a guided entry to the project.
- [Your first analysis](documentation/tutorials/first-analysis.md): install, run a
  small example and inspect its evidence.
- [Pipeline architecture](documentation/explanation/architecture.md): components,
  contracts and an architecture diagram.
- [Scientific methodology](documentation/explanation/methodology.md): what the
  instrument measures and what its results can establish.
- [Capabilities and limitations](documentation/reference/capabilities.md): the
  current engine and web interface have different execution coverage.

## What a result means

`CONFORMANT` means that an evaluated condition has no violation witness in the
recorded observation. `NON_CONFORMANT` means that witnesses exist.
`NOT_EVALUATED` means that evidence or safe evaluation is unavailable. A completed
job is not a claim that the source program conforms, and replay is not a proof
that extraction faithfully represented the source.

The bundled eleven-condition profile is a regression and research baseline.
Conditions are replaceable. New rules over existing facts belong in the formal
layer; new concepts require explicit observation-contract extensions. The
interface reads recorded rule metadata and does not evaluate conditions itself.

## Repository organization

| Directory | Responsibility |
|---|---|
| `src/` | Language observers, shared model, Alloy evaluation, capsules and CLI |
| `apps/research-web/` | Experiment Workspace, its engine bridge and browser controls |
| `tools/` | Required build providers, batch execution, reproduction and diagnostics |
| `documentation/` | Tutorials, guides, explanations, reference and retained historical records |

The former `scripts/` tooling now lives in `tools/`; it was retained because it
includes executable provider and reproduction components. The former flat `docs/`
tree is retained under `documentation/archive/`, with its historical scope stated
explicitly. Read the [source map](documentation/reference/source-map.md) before
changing module boundaries or interpreting older evidence.
The [historical contracts index](documentation/archive/README.md) links to the
retained language, observation and validation records.

## Distribution scope

This public checkout contains runtime implementation and user/scientific
documentation. Qualification tests, performance probes, private work history and
generated validation records remain in the research checkout and are not shipped
here. A successful build of this distribution does not rerun those controls.
References to research controls or dated receipts in the manual and historical
contracts describe that separate qualification environment.

## Build

The engine requires a full JDK 17 and Maven 3.9 or later. Native/provider controls
have additional requirements documented with their tools.

```sh
mvn --batch-mode --no-transfer-progress verify
mvn --batch-mode --no-transfer-progress -f apps/research-web/server/pom.xml package
```

The web application is a separate Spring Boot build. It uses a qualified engine
JAR supplied at startup. See the [tutorial](documentation/tutorials/first-analysis.md)
for exact launch instructions. No React application or Node runtime is required;
Node is used only for browser qualification.

## Research use and contribution

Record source revision, input inventory, dependency/toolchain identities, producer
identity, rule profile, decisions, witnesses and timing for every reported
experiment. Validate source fidelity independently from formal evaluation.
Preserve failures and unknowns in the population. The
[research protocol](documentation/explanation/methodology.md) and
[reproduction guide](documentation/guides/reproduce.md) explain these obligations.

Before modifying extraction or evaluation, run the relevant positive, negative
and unknown controls, then an integration checkpoint. Interface changes must
preserve evidence semantics, accessible native forms and the scientific engine.
See [interface design rationale](documentation/explanation/interface-design.md)
and [configuration reference](documentation/reference/operations.md).

# Pipeline architecture

The scientific engine and Experiment Workspace are separate modules. The engine
owns observation and formal evaluation. The interface owns configuration,
execution records and presentation. A web request cannot redefine a decision.

## Architecture diagram

![Architecture of Experiment Workspace and the conformance engine. The local interface submits isolated jobs. Inputs pass through providers, observers, normalized observations, evidence admission and Alloy evaluation to retained artifacts. Independent source controls validate observation separately from replay.](../assets/architecture.svg)

[Open the architecture diagram at full size](../assets/architecture.svg).

## Scientific data flow

1. **Input and build providers** establish the selected source world, dependencies,
   toolchain and ownership information. A default source-only world and an
   author-build world are different experimental configurations.
2. **Language observers** obtain declaration and native semantic facts for Java,
   Python or C++. Each fact is scoped to its actual supported context. Observers
   report unresolved facts; they do not calculate invariant verdicts.
3. **Normalization** maps those facts to a shared, versioned EMF/Ecore observation
   and preserves contextual identities, provenance and evidence completeness.
4. **Evidence admission** determines whether a registered condition's required
   observations are justified. Missing prerequisites produce no conformance claim.
5. **Formal evaluation** constructs the exact Alloy instance and evaluates
   registered witness functions with the official engine. Structural partitioning
   and reuse are valid only under a qualified rule profile's assumptions.
6. **Publication and interpretation** retain the observation, exact formal model,
   capsule, decisions and witnesses. The capsule is the completed-result marker.

The current baseline uses `observation.xmi`, `repository-instance.als` and
`verification-capsule.json`. Typed views are derived for inspection; they do not
replace the original artifacts.

## Web execution boundary

Spring Boot MVC and Thymeleaf provide server-rendered pages and native forms.
Small same-origin scripts improve selection, search feedback and job status.
The interface does not require a client-side application framework.

The SQLite catalog records projects, reviews and attempts. A persisted queue is
independent of worker capacity. Each reserved worker runs an owned process for
the engine or inspector. Increasing `workbench.compute-workers` creates separate
processes; it does not introduce concurrent Alloy threads inside one JVM.

The engine bridge loads typed results using the configured engine. The inspector
checks inventories and artifact integrity before presenting source links and
downloads. Loopback, origin/session checks, CSRF protection and restricted input
roots define the trusted local deployment boundary.

## Responsibilities and change boundaries

| Change | Responsible component | What should remain unchanged |
|---|---|---|
| Add a rule over existing facts | Formal registry and Alloy rule profile | Language observation and UI verdict logic |
| Observe a new language concept | Versioned schema, adapter and controls | Unrelated providers and scientific outcomes |
| Support a new build context | Input provider and context contract | Rule semantics |
| Improve result presentation | Web inspector, templates and styles | Scientific artifacts and evaluator |
| Change scheduling capacity | Dispatcher and validated configuration | Observation and decision semantics |

This separation is an architectural requirement, not a claim that every inherited
module is free of debt. A shared-contract change requires explicit review of its
consumers and independently specified controls.

## Guarantees and boundaries

Deterministic artifact interpretation is scoped to retained inputs and producer,
schema and formal-profile identities. Formal replay is separate from fresh source
re-observation. A successful exact solve cannot compensate for missing or
incorrectly observed source facts. See [Scientific methodology](methodology.md)
and [Replay and reproduce](../guides/reproduce.md).

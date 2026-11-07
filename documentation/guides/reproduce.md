# Replay and reproduce

Reproducibility has several levels. State which one an experiment establishes.

## Import recorded artifacts

Import copies and integrity-checks matching result files and regenerates typed
inspection views. It verifies the expected transport/profile relationships.
Import does not solve Alloy and does not re-extract the source. Optional source
must match the recorded inventory before source links can be trusted.

## Replay the formal decision

From a completed artifact-bearing result, request replay. A new attempt is linked
to the original; it uses the configured qualified engine and retained capsule.
The verifier checks compatible artifacts and formal execution options before
reconstructing the formal evaluation. Inspect both the replay outcome and logs.

Formal replay supports repeatability of decisions over those artifacts. It does
not establish that the source was faithfully extracted, that dependencies were
complete or that an environment can be reconstructed.

## Re-observe the source

Fresh source re-observation requires the exact source revision and file inventory,
dependency bytes, selected build contexts, compiler/runtime/provider identities,
environment inputs and rule profile. Run the observer again in that declared
world. Compare the typed observations first, then decisions and full witness
tuples. A new timestamp alone is not a semantic difference.

Provider and reproduction tooling is available under `tools/` in the repository.
Its contracts differ by language and corridor. The web interface does not yet
provide a general source-reconstruction workflow. Retaining a source-only
snapshot is not equivalent to retaining an author-build environment.

## Preserve an experiment

Record at least:

- Input revision, source inventory and declared selection/exclusions.
- Consumed dependencies, toolchain/provider identity and configuration.
- Engine artifact hash and formal registry/rule/solver profile identities.
- Observation, exact formal instance, capsule, decisions and full witnesses.
- All attempts, failures, diagnostic messages and operation-specific timing.
- Independent source-semantic expectations and evaluator controls.

Changing an input, engine, provider or profile creates a new experiment identity.
Reuse earlier evidence only when the relevant identities and acceptance conditions
still match. Preserve earlier failures instead of overwriting them with a retry.

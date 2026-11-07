# Scientific methodology

The research question concerns empirical correspondence between source-language
semantics and formalized object-oriented conditions. The pipeline is a measurement
instrument for that question. Its usefulness depends on the validity of each
measurement step, not on producing a high conformance percentage.

## Separate three claims

1. **Observation validity:** the recorded facts faithfully represent the selected
   source/build world within the declared language profile.
2. **Evaluation validity:** the implementation computes the formal condition's
   intended witness relation over that observation.
3. **Empirical interpretation:** the observed population supports the stated
   conclusion about the condition and language.

Internal consistency between XMI, Alloy and a capsule supports the second claim
only within the observation it receives. It cannot independently establish the
first or third claim.

## Define an experiment before executing it

State the theoretical condition and its derivation, language scope, fact
requirements, exclusions, independent expectations and outcome classification.
Specify the sample-selection protocol, revisions, build contexts and toolchains.
Choose positive, negative and unknown controls before looking at their pipeline
outcomes. Keep source-semantic expectations separate from profile expectations.

A source example can be legal under its language and violate a shared formal
condition. For example, Python's abstract-method semantics need not coincide with
the bundled cross-language policy. Such a disagreement must be investigated,
not automatically counted as an extractor defect or silently normalized away.

## Validate each layer independently

Use small source controls with native compiler/runtime observations as an
independent comparison. Compare raw facts, contextual identities, normalized
relations, evidence claims, formal decisions and full witness tuples.

For evaluator changes, compare the optimized route with a qualified reference
under the same inputs, profile and solver configuration. Partitioning equivalence
for one profile does not prove equivalence for arbitrary future conditions.
Formal replay is a separate repeatability control.

Classify disagreements as:

- Extraction or normalization error.
- Evaluator/formalization error.
- Genuine language/profile disagreement.
- Unresolved evidence.

Retain the case and its evidence even when its explanation is not yet known.

## Account for the full population

Record attempted, acquired, observed, evaluable, failed, timed-out and excluded
inputs. State whether each denominator counts repositories, contexts, conditions,
entities or witness tuples. Preserve unknowns. Report sensitivity to sampling and
selection choices. GitHub popularity filters do not establish representativeness.

For paired changes, use identical pinned source revisions and unchanged formal
conditions. Separate improved evidence coverage from changed scientific outcomes.
Investigate dominant disagreements before extrapolating to a language population.

## Treat runtime as part of methodological feasibility

Measure acquisition, dependency/provider preparation, observation, normalization,
formal evaluation, inspection and replay separately. State cold/warm cache
conditions, toolchain, worker count and budgets. Isolate timing measurements from
competing heavy jobs. Report timeout censoring and failures explicitly.

Improve the dominant measured stage with equivalent algorithms, applicable
identity-bound reuse or process-level scheduling. Do not improve reported runtime
by weakening evidence requirements, dropping conditions or excluding hard inputs
without reporting the selection effect.

## Prepare publication evidence

Retain condition-selection provenance, source controls, evaluator controls, input
and artifact identities, the complete outcome accounting and adjudication notes.
Reconcile tables and figures against the same frozen population. State threats to
construct, internal, external and conclusion validity. Explain which reviewer
concerns each experiment answers. A polished interface is not evidence that
those research obligations have been completed.

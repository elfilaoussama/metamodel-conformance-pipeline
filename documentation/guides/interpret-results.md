# Interpret results

Execution state describes an attempt. Scientific decisions describe conditions
over a recorded observation. These are separate quantities.

## Read the overview with its denominator

The overview summarizes the recorded rule profile, decision counts and witness
population. A chart of conditions is not a chart of source files or repositories.
Witness counts are relation tuples; one source entity can participate in several
tuples. Do not interpret the largest chart segment as a defect rate.

| Decision | Interpretation |
|---|---|
| `CONFORMANT` | Required evidence admitted evaluation and the recorded witness relation is empty. |
| `NON_CONFORMANT` | Evaluation produced one or more violation tuples. |
| `NOT_EVALUATED` | Required evidence or safe evaluation is unavailable. No conformance claim follows. |

The interface may label the last category **unknown**. It remains distinct in
counts, charts, details and exports. An empty relation supports conformance only
after its completeness requirements have been justified.

## Investigate a finding

1. Open the condition in **Decisions**. Read its title, required evidence and
   full witness tuples.
2. Follow contextual entity keys into **Model**. Do not merge equal short names
   across compilation contexts.
3. Inspect **Evidence** and diagnostics to determine which facts were observed
   and which remained unresolved.
4. Open **Sources** to inspect retained, hash-matching text when available.
5. Compare the finding with an independent compiler/runtime control and the
   theoretical condition. Classify the disagreement before reporting it.

A finding may be an extraction/normalization error, an evaluator error, a genuine
language/profile disagreement or unresolved evidence. The app does not choose
that explanation automatically. Legal language constructs can disagree with a
shared formal policy.

## Report partial results honestly

Separate the attempted population from completed jobs and evaluable conditions.
Report how many inputs failed, timed out, were excluded or lacked required
evidence. Report unknowns alongside conformant and nonconformant counts. An
interface integration test is not an empirical corpus experiment.

## Inspect the records

The original `observation.xmi`, `repository-instance.als` and
`verification-capsule.json` are the central scientific artifacts. The capsule
binds relevant artifact, producer and formal-profile identities. Available
source inventories, logs, timing and acquisition receipts describe how the
attempt was produced. Preserve these records with the result.

See [Replay and reproduce](reproduce.md) for the distinct guarantees of import,
formal replay and fresh source observation.

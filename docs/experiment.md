# Evaluation

## Overview

The pipeline supports batch evaluation: running the structural condition checks against type models extracted from a set of repositories. Each repository is mapped from its extraction JSON to the intermediate representation consumed by the verification engine, which then reports whether each condition holds (SAT) or is violated (UNSAT), together with per violation descriptions.

The evaluation workflow is tooling-neutral. The `BatchRunner` entry point (`modules/verification-cli`) drives the full map-and-check cycle for a single repository, so any set of repositories with completed extraction can be evaluated.

## Running a Batch Evaluation

1. Build the verification CLI:

   ```bash
   ./modules/verification-cli/setup.ps1   # Windows
   ./modules/verification-cli/run.ps1     # Windows
   ```

   or via Maven: `mvn -pl modules/verification-cli package`.

2. For each repository with a completed `extraction.json`, invoke `BatchRunner` with the repository path and language. It produces the mapped instance and the verification report.

3. Aggregate the per repository reports into a results table (repository, condition, outcome, violation description) for downstream analysis.

## Output

Verification reports record, per repository:

- which conditions were satisfied,
- which were violated, with the violating elements (for example, method names colliding after signature normalisation),
- the total violation count.

## Reproducibility

Corpus definitions, selection parameters, batch results, and figure-generation scripts are study-specific artifacts and are not part of this repository. The verification engine itself is deterministic: the same extraction JSON always yields the same mapping and the same check outcome, so evaluation results are reproducible given the corpus definition and the pinned extraction pipeline version.

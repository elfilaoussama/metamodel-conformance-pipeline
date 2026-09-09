# Recovery protocol for the paper's 224-repository corpus

## Status

The exact **membership** of the empirical corpus has been recovered from the
historical study repository. The exact **source revisions originally ingested**
have not been recovered from tracked study artifacts.

This distinction is mandatory for the corrected experiment. The project must not
claim an exact source-snapshot replication unless the original 224 clone revisions
are recovered independently.

## Historical evidence recovered

The study-specific snapshot is available in repository
`elfilaoussama/metamodel-conformance-pipeline` at commit
`53231103f0c2a785661565f459d881bd16135a3d`.

Its tracked file `experiment/03_selection/selected_repos.csv` contains the selected
repository key, language, historical extracted type count, and stratum for every
member of the paper sample. It is preserved in this repository as
`corpus/paper-224-selected.csv`.

The copy is now verified **byte-for-byte** against the historical Git blob. Both
resolve to Git blob SHA-1:

`d6818780078944b5b12063c780a480dc8c8686eb`

The pinning script and recovery workflow fail before any GitHub lookup if this blob
identity changes.

The recovered membership is:

| Language | Repositories |
|---|---:|
| Java | 75 |
| Python | 75 |
| C++ | 74 |
| **Total** | **224** |

The historical selection used random seed `42` after filtering repositories by the
normalisation intervals and stratifying by type-count tertiles.

The historical batch-result CSVs contain only:

`repo, lang, typeCount, result, violations, elapsed_s`

They do not contain a Git commit SHA.

## Why the original source SHA is missing

The historical JGit ingestion service did compute the clone revision with
`repository.resolve("HEAD")` and stored it in the in-memory `IngestedRepository`.
The clone was shallow (`cloneDepth`, normally 1), on the requested/default branch.
However, the durable extraction model did not contain that revision and the
tracked ingestion/verification CSV formats did not export it.

The old extraction record stored project name, repository path, generation time,
source roots, types, and diagnostics, but not the Git revision. The tracked study
snapshot also does not include the local `analysis-output` directory or cloned
`.git` workspaces from which the revision could now be recovered. The historical
default-branch name was not exported either.

Therefore repository membership is historical evidence; per-repository original
HEAD SHA is not currently historical evidence.

## Historical time bound

`selected_repos.csv` first appears in the study repository at commit
`bf825cb1eb5e69ea2053d457991727c898b94eaa`, committed at
**2026-07-29T12:09:33Z**. Corpus selection necessarily happened no later than that
commit.

This timestamp is used as a conservative, reproducible **selection cutoff** for a
fallback source reconstruction. It is not asserted to be the exact ingestion time
of each repository.

## Reconstructed pinning protocol

`scripts/pin-paper-corpus.py` first verifies the historical selection blob and its
224-row structure. It then resolves, for every recovered repository membership:

1. the repository's canonical GitHub full name at reconstruction time;
2. its default-branch name at reconstruction time;
3. the latest commit reachable from that branch whose committer timestamp is at or
   before `2026-07-29T12:09:33Z`;
4. the resolved commit SHA and commit timestamp.

Every row is explicitly labeled:

`RECONSTRUCTED_SELECTION_CUTOFF`

The generated manifest is therefore an immutable **same-membership historical-cutoff
reconstruction**, not a recovered-original-SHA manifest.

A full run requires an authenticated GitHub token because resolving 224 repositories
exceeds the unauthenticated API rate limit:

```bash
GITHUB_TOKEN=... python scripts/pin-paper-corpus.py
```

For an offline integrity check that performs no GitHub API calls:

```bash
python scripts/pin-paper-corpus.py --validate-selection-only
```

The full pinning run fails unless all 224 memberships are resolved. Deleted,
inaccessible, renamed-without-redirect, or no-pre-cutoff repositories remain
explicit unresolved rows and prevent the reconstructed manifest from being
accepted.

### Reconstruction stability boundary

The reconstruction lookup is **not itself permanently time-invariant** because the
historical study did not persist the original default-branch name. Canonical
repository names and default branches are therefore obtained from GitHub at the
time of reconstruction. A later rename or default-branch change could alter a
future reconstruction lookup.

Consequently, the first complete 224-row reconstructed manifest that passes the
contract must be **frozen as an experiment artifact**, together with its SHA-256
digest. That frozen CSV becomes the authoritative corrected-experiment source
snapshot. Future experiment reruns must use the immutable commit SHAs from that
manifest; they must not silently reconstruct the pins again from mutable GitHub
metadata.

## Scientific interpretation

There are three different replication claims and they must remain separate:

1. **Exact membership replication — recovered.**
   The same 224 selected repository identities, type counts, and strata are known,
   and the recovered selection file is byte-identical to the historical blob.
2. **Exact source-snapshot replication — not currently recovered.**
   The original 224 clone HEAD SHAs were not exported by the historical instrument.
3. **Same-membership historical-cutoff re-execution — constructible and then
   freezable.**
   The fallback procedure resolves one explicit pre-selection-cutoff commit for
   each repository. Once the complete manifest is frozen, those commit pins are
   stable inputs for repeatable corrected experiments.

If an original SHA is later recovered for any repository from an external archive,
log, workstation backup, or published artifact, it must be stored separately with
provenance `RECOVERED_ORIGINAL_SHA`; it must not silently replace a reconstructed
pin without recording the provenance change.

## Corrected empirical campaign

The corrected paper campaign should begin only after the semantic pipeline branch
(O-09 included) and the O-01 bridge-integrity audit have passed the full verification
gate. At that point this recovery branch should be rebased/merged onto that verified
research baseline and the final immutable pin manifest generated and frozen.

For each repository and invariant, the corrected campaign must retain:

- `CONFORMANT`, `NON_CONFORMANT`, or `NOT_EVALUATED`;
- complete-evidence profile and diagnostics;
- total witness count;
- witness technical keys and source provenance;
- source repository canonical name and immutable commit;
- pin provenance (`RECOVERED_ORIGINAL_SHA` or
  `RECONSTRUCTED_SELECTION_CUTOFF`);
- pipeline commit and tool version;
- replayable verification capsule result.

O-09 bridge-correspondence findings and strict override-discipline findings must be
reported separately. O-01 bridge-audit success is instrument validation and must not
be counted as a repository conformance result.

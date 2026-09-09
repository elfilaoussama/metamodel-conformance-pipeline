# Current frozen-corpus validation

This report records the local validation of commit
`54bcef4441555dec4137a60106d438b69a43f270` on 2026-09-04. The commit contains
only schema/version expectation corrections and an architecture-comment cleanup
over integration parent `ceaa113e132bf58af27c99f9b9f024bf332bcdc9`; it does
not change observer or invariant semantics.

## Environment and gates

- OpenJDK and `javac` 17.0.20
- Maven 3.8.7
- Python 3.12.13
- Clang 18.1.3
- `mvn --offline --batch-mode --no-transfer-progress verify`: 132 tests passed
- shaded executable JAR: built successfully
- pinned real-repository verification: replay-valid
- recovered paper selection: 224 repositories structurally verified

Every corpus entry used the immutable repository commit in its checked-in
manifest. Repository build scripts were not executed. Each analysis capsule was
independently replayed, and every report contained exactly the 11 invariants in
the current registry.

## Java corpus

All 20 repositories completed, covering 2,466 Java files. All 20 capsules
replayed successfully, with zero tool, parser-capacity, or Alloy-capacity
failures.

| Invariant | Conformant | Nonconformant | Not evaluated |
|---|---:|---:|---:|
| `exclusive-declaration-ownership` | 19 | 0 | 1 |
| `acyclic-generalization` | 19 | 0 | 1 |
| `local-namespace-uniqueness` | 19 | 0 | 1 |
| `static-abstract-method-separation` | 19 | 0 | 1 |
| `inherited-view-consistency` | 1 | 0 | 19 |
| `local-inherited-separation` | 1 | 0 | 19 |
| `inherited-namespace-uniqueness` | 1 | 0 | 19 |
| `implementation-binding-consistency` | 1 | 0 | 19 |
| `abstraction-implementation-consistency` | 1 | 0 | 19 |
| `override-relation-consistency` | 1 | 0 | 19 |
| `override-discipline` | 1 | 0 | 19 |

The six invariants present in the historical `e68ef120` run have identical
repository-level outcomes. `EVIDENCE_INCOMPLETE` diagnostics decreased from
3,857 to 2,864 (993 fewer, a 25.7% reduction), while the 11 intentional parse
diagnostics in `ManishK4514/Strivers-A2Z-DSA-Sheet` remain fail-closed.
`alpha037/data-structures-and-algorithms` remains the only entry with complete
evidence for all 11 invariants.

## Python corpus

All 6 repositories completed, covering 2,314 Python files. All 6 capsules
replayed successfully.

| Measure | Result |
|---|---:|
| Declaration ownership: conformant | 5 |
| Declaration ownership: not evaluated | 1 |
| Acyclic hierarchy: conformant | 1 |
| Acyclic hierarchy: not evaluated | 5 |
| Evidence-incomplete diagnostics | 101 |
| Parse diagnostics | 12 |
| Explicit external parents | 253 |

All 12 parse diagnostics occur in Black's intentionally invalid or
version-specific parser fixtures. The adapter correctly keeps affected
invariants `NOT_EVALUATED`.

## C++ corpus

All 4 repositories completed, covering 25 C++ source/header files. All 4
capsules replayed successfully.

| Measure | Result |
|---|---:|
| Acyclic hierarchy: conformant | 2 |
| Acyclic hierarchy: not evaluated | 2 |
| Evidence-incomplete diagnostics | 22 |

The two incomplete hierarchy observations are explained by source/environment
boundaries: one repository requires Windows-only `Windows.h`; another contains
`conio.h` and source units that use `std::shared_ptr` without making the
required declaration available to the isolated Clang invocation.

## Interpretation

This run validates the current engineering corpus and the fail-closed evidence
contract. It does not establish population-level conformance rates and does not
replace the future 224-repository paper campaign.

The dominant next implementation gap remains reproducible Java compile-classpath
materialization with artifact provenance. Nineteen Java repositories still lack
complete inherited-member, implementation-binding, and override evidence. The
run found no remaining exact-instance scalability or capsule-replay failure.

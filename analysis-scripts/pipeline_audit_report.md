# Pipeline Audit Report: Java Analysis Pipeline

## Executive Summary

This report audits the full pipeline end-to-end (ingestion, extraction, mapping, verification) across Java, Python, and C++. The most critical finding is a **fatal mapping bug** that renders 91.5% of all Java violations (232,070 out of 253,604) as false positives. Two additional blockers prevent Python and C++ verification from running at all. The audit identifies 14 issues categorized by severity.

---

## Issue #1 [CRITICAL] — AbstractionPolicy: `classParent` read from atom attributes instead of relation tuples

**File:** `InvariantChecker.java`, lines 402–412 and lines 560–586
**Affected invariants:** `AbstractionPolicy`, `OverridePolicy`
**Impact:** 232,070 false-positive violations (91.5% of total)

**Root cause.** The invariant checker's `checkUnresolvedMethods` builds the ancestor chain by reading `classParent` from atom attributes:

```java
// InvariantChecker.java:406
Map<String, String> cAttrs = model.atomAttrs.get(atom);
if (cAttrs != null) {
    String parent = cAttrs.get("classParent");  // ← ALWAYS NULL
    while (parent != null) { ... }
}
```

But the mapper (`JsonToAieMapper.java:231`) writes `classParent` as a **relation**:

```
classParent[Class1] = Class0
```

Atom attributes only contain `name`, `isAbstract`, and `cid`. The `classParent` key does not exist in atom attributes. Therefore `parent` is always `null`, `visibleImplementers` contains only the classifier itself, and every inherited non-abstract method triggers a violation because the checker cannot see the parent's `ImplementationBinding`.

The same bug exists in `isSubtypeOf` (line 579), making override return-type covariance checks unreliable.

**Fix.** Change `checkUnresolvedMethods` to walk the `classParent` relation tuples (`model.getTuples("classParent")`) instead of atom attributes. Apply the same fix to `isSubtypeOf`.

---

## Issue #2 [CRITICAL] — Python verification never invoked

**File:** Pipeline orchestration (desktop app / batch runner)
**Impact:** 563 Python repos extracted but ZERO verified

The ingestion CSV for Python shows `Status=COMPLETED, Activity=Extraction completed (no verification)`. Extraction succeeds — 563 JSON files exist in `analysis-output/python/`. But the verification step (`AlloyInEcoreVerificationService`) is never called for Python repos. The pipeline only dispatches verification for Java.

**Fix.** Wire the verification step into the Python processing path. The Python extraction JSONs are structurally identical to Java's (same `ExecutableModel`, `FieldModel`, `ParameterModel` schema), so the mapper should handle them without code changes.

---

## Issue #3 [CRITICAL] — C++ extraction fails for 74.5% of repos

**File:** `CppExtractionService.java` / `extract_cpp.py`
**Impact:** 447 of 600 C++ repos failed to extract. 153 succeeded but none were verified.

The ingestion CSV shows `Status=FAILED, Activity=Could not ingest` for 447 repos. Possible causes:
1. Clang/libclang not installed on the execution environment
2. `extract_cpp.py` has unmet Python dependencies
3. The C++ repos may contain code that crashes the script (no error recovery)

The 153 that succeeded have valid extraction JSONs with types, executables, and fields — confirming the extractor CAN work when conditions are met.

**Fix.** Install Clang/libclang on the pipeline host. Add dependency checks and meaningful error messages to `extract_cpp.py`. Wire verification after extraction, same as Java.

---

## Issue #4 [HIGH] — Language column in Python ingestion CSV always `JAVA`

**File:** Pipeline CSV export logic
**Impact:** Cosmetic but confusing — all 563 Python repos labeled `Language=JAVA`

The extraction JSONs contain correct Python data (tested 3 random repos: `KlubJagiellonski__pola-backend`, `Egoist-Machines__LodeDB`, `oaslananka__kicad-mcp-pro` — all confirmed Python). The CSV export uses a fixed label regardless of actual language.

**Fix.** Read the language from the extraction service's `getLanguage()` return value, not a hardcoded string.

---

## Issue #5 [HIGH] — C++ ingestion CSV uses full GitHub URLs as repo names

**File:** Pipeline CSV export logic for C++
**Impact:** Inconsistent with Java/Python which use `owner__repo` format

C++ CSV uses `https://github.com/owner/repo.git` while Java/Python use `owner__repo`. This breaks cross-language aggregation scripts.

**Fix.** Normalize C++ repo names to `owner__repo` format before writing the CSV.

---

## Issue #6 [HIGH] — AbstractionPolicy violations hide meaningful signal

**File:** `InvariantChecker.java` — `checkAbstractMethodInAbstractClass` (line 440–454)
**Impact:** This specific sub-check ("Non-abstract classifier contains abstract method") is conceptually correct. But it is conflated under the same `AbstractionPolicy` label as the false-positive cascade from Issue #1. We cannot distinguish true structural defects from mapping errors.

**Fix.** Apply the Issue #1 fix first. Then split `AbstractionPolicy` into two invariant names: `UnresolvedMethodPolicy` (for the ancestor-chain binding check) and `AbstractMethodInNonAbstractClass` (for the abstraction consistency check).

---

## Issue #7 [MEDIUM] — `JavaExtractionService` interface name is misleading

**File:** `JavaExtractionService.java` in `analysis-core`
**Impact:** `PythonExtractionService` and `CppExtractionService` both implement `JavaExtractionService`. Confusing for maintainers.

**Fix.** Rename to `ExtractionService` or `LanguageExtractionService`.

---

## Issue #8 [MEDIUM] — No `superClass` emission for Python classes without base

**File:** `extract_python.py`, lines 158–166
**Impact:** Python classes that don't explicitly declare a base class get `superClass = null`. Conceptually, all Python 3 classes implicitly extend `object`, but the mapper handles `null` gracefully.

**Mitigation.** Current behaviour is acceptable for the kernel (no `classParent` relation is emitted, and the kernel's checks don't require a universal root). Document as a known design decision.

---

## Issue #9 [MEDIUM] — Python field type is `unknown` for unannotated assignments

**File:** `extract_python.py`, line 197–198
**Impact:** Fields without type annotations get `type: "unknown"`. This prevents meaningful type-based checks (e.g., return-type covariance). For untyped Python code, this is expected.

**Mitigation.** Document as limitation of dynamic typing. The kernel's type-agnostic checks (ownership, namespaces, conflict) are unaffected.

---

## Issue #10 [MEDIUM] — Python `_kind` heuristic classifies ABC as `interface`

**File:** `extract_python.py`, lines 66–78
**Impact:** Python abstract base classes tagged as `kind=interface` rather than `kind=class` with `abstractType=true`. This triggers `InterfacePolicy` violations if they contain non-abstract methods (which ABCs can legally have in Python).

**Fix.** Separate the ABC detection from the interface classification. Tag ABCs as `kind=class, abstractType=true` and reserve `kind=interface` for classes inheriting `Protocol` or using `@typing.runtime_checkable`.

---

## Issue #11 [LOW] — Spoon extraction: constructor bodies not mapped to ImplementationBinding

**File:** `JsonToAieMapper.java`, line 87
**Impact:** Constructor executables (`constructor: true`) are explicitly skipped in mapping: `if (getBoolean(m, "constructor", false)) continue;`. This is intentional (O-06 ImplementationDomainValidity applies to methods, not constructors). No violation.

**Documentation.** Add a comment explaining why constructors are excluded.

---

## Issue #12 [LOW] — Spoon extraction: record types collapsed to `kind=class`

**File:** `SpoonJavaExtractionService.java`, line 136–137
**Impact:** Java records are mapped as `kind=class` (the JSON has `kind=record` from Spoon but... wait, looking again at line 136: `if (type instanceof CtRecord) return "record";` — it DOES map to "record". But the mapper at line 38–47 maps non-interface types as `ClassN` atom prefix. Records would get `ClassN` prefix since `kind != "interface"`. This is semantically acceptable since records are classes for structural analysis purposes.

**Documentation.** Document that records and enums are collapsed into the general `Classifier` type at the metamodel level.

---

## Issue #13 [LOW] — Mapper creates `MethodBody` + `ImplementationBinding` for Java interface default methods

**File:** `JsonToAieMapper.java`, lines 159–166
**Impact:** The mapper only creates bindings for atoms where `isClass.get(ti)` is true (line 160). Interface atoms are skipped. This means Java 8+ default methods (concrete methods on interfaces) won't get ImplementationBinding atoms. However, the kernel's `InterfacePolicy` check at line 456–470 already catches non-abstract methods on interfaces. This is a correct-but-incomplete mapping: default methods are legitimate Java constructs that violate the kernel's `InterfacePolicy`.

**Design decision.** The kernel considers all interface methods as abstract (O-07 derivation from six traditions). Java default methods are a language-specific relaxation. Document as a precision gap, not a mapper bug.

---

## Issue #14 [LOW] — No `paramTypes` emitted for Python/C++ methods in Alloy AIE

**File:** `JsonToAieMapper.java`, line 106, `buildParamTypes`
**Impact:** The mapper reads `parameters` from the JSON and builds `paramTypes` correctly: `{0 = "int", 1 = "String"}`. This works for Java (Spoon JSON has `parameters` array). Python extraction JSON also has `parameters` (line 182 of extract_python.py). C++ extraction JSON presumably has them too. All three should work.

**Verification status.** The Python/C++ extraction JSON structure is compatible with the mapper. The issue is only that verification is never invoked (Issues #2, #3).

---

## Summary of Action Items

| Priority | Issue | Action |
|----------|-------|--------|
| 1 | #1 — `classParent` atom attribute bug | Fix `checkUnresolvedMethods` and `isSubtypeOf` to read relation tuples |
| 2 | #2 — Python verification never runs | Wire verification after Python extraction |
| 3 | #3 — C++ extraction fails 74.5% | Install Clang, debug script, wire verification |
| 4 | #4 — Language column cosmetic bug | Fix CSV export to use actual language |
| 5 | #5 — C++ repo name format | Normalize to `owner__repo` |
| 6 | #6 — Split AbstractionPolicy | Separate into two distinct invariant names |
| 7 | #7 — Rename interface | `JavaExtractionService` → `ExtractionService` |

---

## Fix Verification

The two critical fixes (Issue #1 and #1b) have been applied to `InvariantChecker.java`:

- **checkUnresolvedMethods** (line 364-438): ancestor chain now built from `classParent` relation tuples instead of atom attributes.
- **isSubtypeOf** (line 560-586): same fix for override return-type covariance checking.

Build result: `mvn test` in `modules/verification-cli` — **35/35 tests pass, BUILD SUCCESS**.

The existing tests do not exercise the ancestor-chain binding visibility path, so the real validation will come from re-running verification against the ingested Java corpus after rebuild.

## Additional Analysis: C++ Extraction

The `extract_cpp.py` script (275 lines) uses libclang to parse C++ headers. Key findings:

- **Format compatible** with the mapper: produces the same JSON structure as Java and Python extraction.
- **Dependency**: requires `pip install libclang`. If not installed, script exits with error code 1.
- **Failure rate**: 447/600 repos (74.5%) failed at the INGESTION stage (clone failed), not the extraction stage. Possible causes: GitHub rate limiting, deleted repos, or network issues. The 153 repos that were cloned all produced valid extraction JSONs.
- **Concurrency risk**: libclang's `cindex.Index.create()` is not thread-safe when called from multiple JVM processes. The pipeline runs extraction sequentially per repo, so this is not an immediate concern at current throughput.
- **Missing verification**: same as Python — verification is never invoked for C++ repos.

# Current C++ native projection — adapter 0.9.0 / schema 14

Source declarations are qualified by the analyzed translation-unit configuration,
actual compiler bytes and any declared execution layout. Shared physical header
origins stay visible as source paths/lines; matching declarations in separate
invocations are never silently merged. Parent resolution stays within a context.
One configuration per source path remains the supported provider contract;
this is not linker aggregation or a whole-program namespace.

Native signatures separate ordered actual parameter types from receiver CV/ref
qualifiers. Exception specifications are neither parameters nor receiver qualifiers.
Only a restricted simple Clang function-type spelling is interpreted for receiver
qualifiers; complex return declarators yield UNKNOWN receiver evidence without
discarding known ParmVarDecl parameters. Unsupported parameter subsets remain
UNSUPPORTED. Fixed P0 uses its unchanged parameter-only key: legal qualifier
overloads can produce genuine local-namespace findings.

The native envelope retains ordered direct-base occurrences, compiler-emitted type
spelling, access, virtuality and contextual resolution status. Fixed P0 still uses
the unordered resolved parent graph. Native receipts grant no new EvidenceKind.
Return types, bodies, lookup and roles are not fabricated. Domain input digests
describe recorded configuration/compiler/byte subsets; runtime, timestamp/clock,
negative-probe and generated/support-role closure are explicitly unverified.

The paragraphs below retain the earlier 0.5.0 contract history. Its transport
details are superseded by the later ordered-v4/configured-input-layout contract;
its former global declaration-merging behavior is superseded here.

# Earlier C++ context evidence contract — adapter 0.5.0

The observer measures source declarations under the supported host/compiler
profile. It consumes declared source-root-relative include directories, ordered
macro definitions and a language standard from the resolver manifest. Other
units retain the explicit default `-std=c++17 -I <source-root>` profile. It does
not reconstruct arbitrary compiler/build-system semantics.

One context per source path is supported. Multiple configurations for one path
are rejected, including equivalent duplicate rows with different context IDs.
They must be analyzed as distinct attempts until a versioned context-specific
identity/merging contract exists. Last-row-wins is never evidence selection.

Every actual analyzed TU or fallback header has a virtual input unit
`cpp-context/<SHA256(root-relative-source-path)>`. Its digest fingerprints UTF-8
JSON of the record fields, in this order: `schemaVersion` (`"1"`), `sourcePath`,
`workingDirectory` (`"."`), `declared` (boolean), `arguments` (ordered strings).
Arguments are constructed by the same helper as the actual invocation, with
source-root-relative include paths rooted at `.` rather than the temporary
physical root. The record contains the supported variable `-std`, `-D`, `-I`
arguments; fixed syntax/AST/dependency flags are part of the adapter/profile
implementation identity. Compiler bytes, host target/version and actual include
bytes retain their existing fingerprints. Retain the dependency manifest with
the source/provider receipt to reconstruct declared contexts. A virtual context
unit is input provenance, never a classifier, member or invariant decision.

This distinguishes unused macro changes as well as changes producing different
facts, and avoids temporary-root placement in the context digest. External-header
virtual paths still include physical path identity under the existing contract;
whole input identity is not certified invariant under relocating external headers.
Unconsumed manifest metadata/context IDs do not stand in for actual compiler
arguments. Broader environment/flag/provider completeness remains a separate gate.

Own declaration, signature, visibility/access projection, method abstraction and
method scope completeness are independent of unresolved base names. No compiler
failure, malformed/unsupported identity, include-fingerprinting failure or
disagreement in repeated declaration shapes may be hidden by this separation.
Those failures still withhold the affected observation domain.

Conditional local facts are claimed only when every analyzed TU is declared and
no uncovered header required default standalone fallback, and all observations
are otherwise complete and consistent. This is a selected-context source view,
not a claim about declarations in every configuration. Source-only/partially
declared conditional projects retain conservative withholding. Configuration-
independent hierarchy remains incomplete for non-guard conditionals in either
profile. A future selected-configuration hierarchy contract requires explicit
review and controls; it is not silently introduced here.

An external-parent name hint is recorded but cannot certify an unobserved parent
graph. Unresolved external/template bases remain explicit, keep `HIERARCHY`
incomplete and do not silently become target classifiers. Fully observing support
classifiers and independently inherited/override/body facts remains future work.

The documented compound-signature projection preserves trailing C++ cv/ref/
exception qualifiers in the token sequence. These are not native parameters.
Likewise, current access/inheritability projection is not native override evidence:
private virtual members can be overridden. The current P0 profile remains fixed;
language/profile correspondence limitations must be reported separately.

Calibration and retained failure evidence live in
`validation/java-cpp-context-calibration-2026-10-09/`. See its contract/result for
exact fixture coverage and limits. This change does not certify complete CMake
provider behavior, arbitrary C++ code, concurrent execution or paper readiness.

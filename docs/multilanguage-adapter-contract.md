# Multilanguage adapter contract

This branch extends the source-observation boundary without changing the semantic
pipeline. Java, Python, and C++ observers feed the same canonical observation
contract:

```text
source -> language observer -> canonical observation.xmi
       -> invariant registry + exact Alloy evaluation
       -> witnesses/provenance -> verification capsule
```

No language receives a private invariant evaluator. Alloy remains the sole semantic
authority.

## Adapter obligations

A language observer may mark an `EvidenceKind` complete only when the corresponding
facts are independently observable from the configured source and dependency
boundary. Unsupported, ambiguous, or unresolved facts remain incomplete and every
invariant requiring them is `NOT_EVALUATED`.

In particular:

- `DECLARATION_OWNERSHIP` requires observable local declarations and their owners.
- `HIERARCHY` requires a complete direct-generalization relation for the declared
  boundary; unresolved bases or parents keep hierarchy-dependent invariants
  unevaluable.
- `LOCAL_SIGNATURES` requires stable member names and ordered parameter-type
  identities. A dynamic language must not invent static types merely to satisfy
  this evidence kind.
- `INHERITABILITY` and visibility must be mapped from the language's own semantics,
  not copied mechanically from Java rules.
- `INHERITED_MEMBERS` must come from an independent semantic resolution mechanism
  appropriate to the language. It must not be populated by reusing the Alloy
  relation that it is intended to validate.

## Canonical schema v8

Observation schema v8 adds `MemberVisibility.UNKNOWN`. This value exists so a
frontend can record a source declaration without fabricating Java visibility
semantics. Alloy encodes it as a distinct `VISIBILITY_UNKNOWN` atom. Invariants that
require accessibility or inherited-member semantics remain gated by their evidence
requirements, so unknown visibility cannot silently participate as public,
protected, package, or private visibility.

The schema change is paired with pipeline tool version `0.10.0`. Verification
capsules remain exact-version replay artifacts; archived capsules are not silently
migrated across tool/schema boundaries.

## Technical identity versus empirical identity

Generated classifier/member keys exist only so artifacts can refer to atoms
stably. They are not empirical identifiers and must never be used to make the
identity invariant executable. Any future identity invariant requires an
independently justified source-level identifier for each relevant carrier.

## Language-specific boundaries

### Java

The Java observer remains the default. Spoon observes declarations; `javac`
independently observes inherited membership. Explicit dependency JARs are validated
and fingerprinted into the canonical source set. Java continues to provide its
existing visibility, signature, inheritability, and inherited-member evidence; the
move from schema v7 to v8 does not weaken those claims.

### Python

The Python adapter uses CPython's standard `ast` module as a non-executing source
frontend. It never imports or executes the repository under analysis.

The current implemented boundary contains two independently gated evidence slices:

1. **Classifier hierarchy.** Source declarations have stable definition identities,
   runtime bindings are tracked separately, and straight-line aliases/redefinitions
   plus conservative identity-preserving `dataclass` decoration are handled.
   Absolute and relative imports are resolved according to their distinct Python
   semantics. Dynamic/shadowed bases and unallowlisted external bases keep only
   `HIERARCHY` incomplete; they do not erase independent declaration evidence.
2. **Declaration ownership.** Canonical Python members in this slice are source
   declarations directly observable in class bodies: `def`, `async def`, simple
   assignment targets, and annotated assignment targets. Nested classes own their
   own declarations rather than contributing them to the outer classifier.
   Methods and attributes receive stable source-derived technical keys and exactly
   one declaring classifier.

Python member observations deliberately use `Inheritability.UNKNOWN` and
`MemberVisibility.UNKNOWN`, and parameter-type lists remain empty. Therefore the
adapter may claim `DECLARATION_OWNERSHIP` after parse-clean extraction while
`LOCAL_SIGNATURES`, `INHERITABILITY`, and `INHERITED_MEMBERS` remain incomplete.
This makes `exclusive-declaration-ownership` independently evaluable without
pretending that Python has Java-style signatures or visibility.

Runtime-created attributes, descriptors, metaclass effects, monkey patching, and
other execution-dependent namespace mutations are outside this source-only member
slice. Extending the modeled Python member vocabulary requires an explicit contract
change rather than silently treating runtime behavior as statically observed.

### C++

The C++ adapter uses Clang's compiler AST and currently claims only class/struct
direct hierarchy evidence. It does not implement declaration ownership, signatures,
visibility, inheritability, override evidence, or independently observed inherited
membership yet.

The declared C++ profile is C++17 with the repository root available as a project
include root and the concrete host Clang include environment available for parsing.
This is intentionally different from the original `-nostdinc/-nostdinc++` prototype:
real repositories commonly require the C++ standard library even when the modeled
class hierarchy itself is entirely project-local.

Using host headers does not make them invisible evidence. The observer fingerprints
the resolved Clang executable and every external header reported by Clang's actual
dependency set. Project headers reached through successful translation units are
also represented in the canonical source set. The JSON AST is consumed as a stream
so standard-library AST size is not an arbitrary observation limit.

This remains a source-profile observer, not a compilation-database observer.
Project-specific command-line macros, generated include paths, compiler flags, and
other build-system configuration are never guessed. Non-guard conditional
preprocessing, compiler failures under the declared profile, ambiguous internal
base identities, and dependent/template bases keep `HIERARCHY` incomplete. Ordinary
include guards are treated as structural guards rather than configuration choices;
nested or independent conditionals still fail closed.

External parent allowlisting can close an explicitly named parent boundary, but it
cannot hide ambiguous internal identities or template-dependent bases. A future
compilation-database slice must fingerprint the exact translation-unit commands
before it can strengthen this evidence boundary.

## CLI selection

`analyze` accepts an optional canonical source-language selector:

```text
--language java|python|cpp
```

`java` is the backward-compatible default. Python supports conservative classifier
hierarchy plus source-declaration ownership. C++ uses the compiler-backed Clang
hierarchy observer described above. Unsupported or configuration-dependent evidence
remains `NOT_EVALUATED` rather than being guessed.

## Extension rule

Adding a language adapter may change observation code and language-specific tests.
It must not add language-specific invariant branches to `ConformancePipeline`, the
Alloy evaluator, or the registry machinery. If a language exposes genuinely new
facts needed by a future invariant, the canonical observation schema must be
extended explicitly and all adapters must state whether that new evidence kind is
complete, incomplete, or unsupported for their configured boundary.

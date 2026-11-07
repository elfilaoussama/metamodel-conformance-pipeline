# ADR 0001: Use a minimal EMF observation contract

Status: accepted

## Decision

Language adapters populate one versioned Ecore metamodel. They do not infer a
domain metamodel from arbitrary source code. The first schema records:

- source units and SHA-256 digests;
- declared classifiers and stable identifiers;
- inheritance edges between declared classifiers;
- frontend-resolved inherited-member memberships, kept separate from declaration ownership;
- unresolved parent references with source locations; and
- adapter identity and version.

No method bodies, comments, or source text are stored. Classifiers and edges are
sorted before serialization. Identifiers derive from language, normalized
relative path, classifier kind, and qualified name.

## Boundary

The source root is a closed observation boundary. Every non-platform parent must
either resolve to a classifier inside the boundary or appear in the explicit
external-parent allowlist. Missing evidence is represented, never discarded.

This boundary makes adapters replaceable while keeping the downstream Alloy
encoding independent of Java, Python, or C++ parser APIs.

For Java, inherited memberships are a second-front-end observation: Spoon records
declarations, while javac's language-model API resolves the inherited view. The
pipeline does not derive both sides of the inherited-view invariant from the same
algorithm.

Visibility and package are canonical source facts. Contextual Java accessibility is
derived in Alloy, including the requirement that package-private inheritance cannot
cross and later re-enter a package boundary. Compiler failures are retained as
evidence diagnostics rather than silently collapsing the inherited relation to empty.

## Security and determinism

- Real paths must remain beneath the declared source root.
- Symbolic-link source files are rejected.
- Output contains hashes and normalized relative paths, not source contents.
- Source paths use canonical `/`-separated relative form; absolute paths,
  backslashes, empty segments, `.` segments, and `..` segments are rejected.
- Source-unit paths are unique, SHA-256 values are lowercase 64-hex digests, and
  classifier/member technical identifiers use their prefixed digest forms.
- Every classifier, member, unresolved reference, and diagnostic source path
  resolves to a source unit included in the hashed observation boundary.
- Serialization contains no timestamps or machine-specific absolute paths.
- Schema and adapter versions are explicit compatibility inputs.

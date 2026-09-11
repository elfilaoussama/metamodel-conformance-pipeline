# Java compilation-context isolation milestone

This local milestone builds on `08d225b`. It changes observation and compiler
reconstruction, not Alloy invariant definitions. No Actions, push, or merge is part
of this work.

## Problems reproduced before correction

1. A future-language context erased declarations from an independent supported context.
2. Independent duplicate qualified names forced per-file recovery and lost valid
   package-annotation companions.
3. A context compiled successfully against a sibling upstream context despite having
   no dependency edge to that sibling. One shared output directory caused this leak.
4. A declared module-path entry continued pointing to an unavailable original output,
   while the freshly compiled upstream module was supplied as classpath evidence.

## Completed behavior

- Structural parsing uses each context's source/release and preview settings instead
  of the maximum language level across the repository. Recovery stays within that
  context. Source units and supported declarations survive independent failures.
- Identical shared declarations are deduplicated using their source locations and
  structural AST equality. Conflicting interpretations are removed from the merged
  declaration set and explicitly diagnosed. Context ordering does not choose a winner.
- Unowned sources remain fingerprinted and may yield lexical facts, but their missing
  context ownership prevents a complete-evidence claim.
- Each upstream compilation writes to a separate temporary directory and sees only
  its own declared transitive upstream closure. Adding the missing edge in the
  negative fixture restores compilation with distinct output directories.
- Exact declared output paths are substituted with reconstructed outputs in their
  recorded role and position: classpath, module path, upgrade-module path, processor
  path, and qualified patch-module entries. Ambiguous output identities are rejected.
  No archive or directory-containment heuristic is used.
- The existing compatibility behavior for an upstream edge without an explicit path
  is retained: its output precedes external classpath entries. Explicit path slots
  retain their recorded ordering instead. An empty boundary uses an isolated empty
  directory, preventing access to the analyzer process's dependency classpath.

Adapter versions: structural `0.12.0`, implementation `1.6.0`, dependency-aware `1.10.0`.

## Local evidence

The broad Linux/JDK-17 JUnit run passed 169 tests with zero failures/errors, excluding
only `ClangCppObserverTest` and `PipelineCliCppTest` because clang++ is absent.
Production and test classes were freshly compiled against the retained dependency
bundle. This is direct local verification, not a Maven verify claim.
After the broad run, the strengthened upstream/classpath-order checks passed in a
five-test targeted run; the final context and module fixtures passed in a nine-test
run using the complete dependency-aware observer. The rebuilt executable JAR also
passes the local corpus-fallback gate.

New tests cover independent unsupported contexts, duplicate qualified names with
package annotations, identical and conflicting shared declarations, unowned sources,
undeclared sibling dependencies, named modules, and ambient-classpath isolation.
The classpath-order fixture also checks both implicit precedence and explicit ordering.

Two end-to-end fixtures preserve exact observations through EMF/XMI and obtain
CONFORMANT decisions from all registered Alloy invariants:

- Independent nested hierarchies with the same qualified names, using Java 11 and 17 contexts.
- A named application module consuming an independently compiled named library module
  through an explicitly recorded module-path entry.

Architecture, no-special-case, compilation-context, javac-semantic, dependency-resolver,
and all nine Gradle emission checks pass locally. These fixtures are authored local
inputs; they are not substitutes for the real-provider corpus.

## Remaining acceptance gates

- Maven/Gradle providers must actually emit faithful module roles and compiler options.
  The engine now honors explicit output roles; it does not infer missing provider facts.
- Exact Gradle archive/variant ownership remains unresolved. Missing or ambiguous
  artifacts must still fail closed.
- Missing annotation declarations, unsupported Java features/toolchains, or genuinely
  conflicting variants can still prevent complete evidence. Global invariant decisions
  remain conservative if any required evidence is incomplete; preserving declarations
  from a good context does not make a partially observed repository conformant.
- Medium, pinned real repositories still need local isolated-provider validation.
  Docker remains unavailable here. No previous corpus outcome is relabeled by this work.
- Only after those gates should the complete local delta be reviewed against the
  current remote Java baseline. This checkout's snapshot ancestry does not prove a
  remote merge base. Main promotion remains a separate foundation decision.

# Local correctness and integration checkpoint — 2026-09-09

## Authority and baseline

No GitHub Actions runs, pushes, PRs, or merges were initiated during this review.
The existing dirty checkout was preserved. This separate checkout was reconstructed
from remote commit `965d9100ae2b8f10c9184eca1c1903e7684d3b42`.
All 199 tracked blobs and executable modes were checked; its baseline tree is
`5660a219b64c9db8ad497eb6731c3cc1f66c9d97`.
The local snapshot commits are not reconstructed remote ancestry.

## What the existing green run actually establishes

Run [34337732262](https://github.com/OsmGetHub/metamodel-conformance-pipeline/actions/runs/34337732262)
completed successfully before this review. It reports four resolved repositories,
2,680 Java files, 306 dependency JARs, and 76 compilation contexts.
However, 39 of 44 invariant decisions are NOT_EVALUATED. Eight invariants are
unevaluated in every repository. The provider gate is not a project-completion gate.

| Repository | Diagnostic evidence from that run | Interpretation |
|---|---|---|
| Gson | 523 diagnostics, including missing project symbols and non-unique classifier mapping | Production/test visibility and identity handling need investigation |
| Caffeine | 710 diagnostics, including `Unrecognized option : -26` | Resolver success does not establish frontend support for observed compiler semantics |
| ArchUnit | Parser rejects identifier `package-info` | A frontend issue blocks subsequent evidence enrichment |
| Jackson | 203 diagnostics, including missing named module and unreadable unnamed-module dependency | Module-path/compiler semantics are not faithfully reconstructed |

These are not four successful empirical conformance evaluations.
The exact reports remain attached to the historical run; they must not be replaced
or relabeled after local corrections.

## Reproduced and corrected locally

1. Gradle silently omitted an unavailable internal archive when no main context
   existed. The missing dependency is now retained and rejected by manifest validation.
2. Gradle mapped custom artifact variants to main based only on project identity.
   Project identity is not enough to prove a source-set variant.
3. Gradle converted existing archives to main-context links based on build-directory
   containment. Archive evidence is now preserved; only exact observed output-directory
   identities produce upstream links. Archive-to-context variant mapping is not implemented.
4. Maven emitted test contexts without their own module's observed compile context.
   Added that upstream edge within the Maven provider, consistent with
   `MavenProject.getTestClasspathElements()` in Maven 3.9.9. A test-only effective
   model does not acquire a nonexistent compile-context edge.

The Gradle correction is deliberately fail-closed. It can cause the previously
green provider corpus to reject unavailable internal JARs again. That is an honest
limitation until exact artifact/variant ownership or trustworthy built artifacts
are available; it is not grounds to drop the dependency.

## Local verification

- Production and test Java sources compiled on Linux/OpenJDK 17.
- JUnit console execution: 153 tests passed, no failures, excluding exactly
  `ClangCppObserverTest` and `PipelineCliCppTest` because clang++ is absent.
- Seven behavioral checks execute the actual Groovy manifest-emission closure:
  exact output, custom output, missing ownerless archive, custom archive,
  existing archive, external archive, and own output.
- The three unsafe Gradle cases failed against the remote candidate before the fix
  and pass after it. Missing-archive cases also exercise the real manifest validator.
- Maven regression failed before its correction and passes afterwards; a test-only
  module is also checked.
- Resolver contract, Java architecture boundary, no-special-case audit, javac
  semantics, compilation-context contract, and corpus-fallback test pass locally.
- `git diff --check` passes.
- This is not a Maven verify claim: JUnit ran against freshly compiled classes
  using the existing dependency bundle and JUnit Platform 1.11.4.
- Groovy 4.0.24 is a test-only runtime. The closure harness does not claim to
  reproduce Gradle configuration, task execution, or container isolation.
- Real-provider smoke was attempted locally and returned exit 69: Docker unavailable.
  No hosted run was used to bypass that blocker.

## Reproduction

Supply an existing project dependency bundle, JUnit console runtime, and Groovy runtime.
Compile `src/main/java` and `src/test/java` into separate directories; place newly
compiled classes and repository resources before the dependency bundle on the classpath.
Run JUnit with `--scan-classpath=target/test-classes`, excluding only the two classes above.

```sh
bash scripts/test-java-dependency-resolvers-local.sh .
bash scripts/audit-java-architecture-boundary.sh .
bash scripts/audit-no-special-cases.sh .
bash scripts/validate-javac-semantics.sh .
bash scripts/test-java-compilation-contexts-local.sh .
java -cp "$GROOVY_TEST_JAR" groovy.ui.GroovyMain scripts/test-gradle-emission-local.groovy .
bash scripts/test-java-corpus-fallback-local.sh .
git diff --check
```

The fallback test needs a built executable pipeline JAR. For this review a local
JAR was assembled from freshly compiled classes/resources and the retained dependency
bundle; it is not an artifact from a new Maven or Actions build.

## Merge decision and remaining work

PR #1 was already merged into `feature/java-evaluability-local-gated`
at `0fa8abdbc3484f87195d0484bad05dc188908f58`.
The published build-backed branch is a descendant: 12 commits, 13 changed files,
no divergence and no C++/Python production changes in that delta.
A future merge into this Java baseline can preserve all existing history.
Temporary validation branches contain workflow triggers and must not be merged.

Do not merge the published candidate as-is: it still contains the unsafe Gradle
mappings. Local corrections are unpublished. Do not promote to main:
main is still `cba106a78f392dbe5c161853f37265a0f35d485d`, and promotion
would import 288 commits of the wider foundation.

Remaining priority order:

1. Validate exact Gradle artifact variants without discarding evidence; use small local
   behavioral fixtures and, when available, Docker-capable local provider smoke.
2. Reproduce the frontend/compiler/module/identity diagnostics above with minimal
   local fixtures. Do not weaken NOT_EVALUATED or alter source semantics to obtain green.
3. Recheck four measured-size, runtime-compatible real repositories locally. Preserve
   historical failures; do not claim the prior Caffeine Java-26 input is JDK-17 evaluable.
4. After end-to-end evidence is sound, integrate only the Java milestone into the
   Java baseline. Main promotion and C++/Python integration remain separate decisions.

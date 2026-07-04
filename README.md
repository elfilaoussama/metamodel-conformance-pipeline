# Java Analysis Platform

A modular Java 17 platform for ingesting GitHub repositories and extracting
source-code models with Spoon. The primary application is a cross-platform
Swing desktop interface; ingestion and extraction are independent libraries.

## Current milestone

Implemented:

- queued GitHub repository ingestion through JGit;
- advanced GitHub repository search with optional token authentication,
  qualifiers, pagination, result limits, and selective queue insertion;
- shallow clones with explicit reuse/fail policies;
- Maven multi-module source-root discovery;
- Spoon extraction of types, inheritance, fields, methods, constructors,
  qualified types, and source locations;
- versioned, UTF-8, atomically written JSON output;
- optional AlloyInEcore conformance verification using a metamodel selected at
  runtime, with named violations in JSON, CSV, and the desktop result table;
- background Swing execution, progress reporting, cancellation, logging, and
  persistent folder preferences;
- a self-contained executable JAR.

## Modules

| Module | Responsibility |
| --- | --- |
| `modules/analysis-core` | Stable domain records, service interfaces, progress and cancellation contracts |
| `modules/github-ingestion` | GitHub/JGit implementation of repository ingestion |
| `modules/github-search` | GitHub REST repository search, query construction, paging, and rate-limit metadata |
| `modules/spoon-extraction` | Source-root discovery, Spoon model extraction, JSON serialization |
| `modules/verification-integration` | Cross-platform, cancellable adapter for the isolated AlloyInEcore runtime |
| `apps/swing-desktop` | Cross-platform desktop workflow and executable packaging |

See [Architecture](docs/architecture.md) for dependency rules and extension points.

## Requirements

- JDK 17+
- Maven 3.9+
- Network access to GitHub for new clones

## Build and test

```powershell
.\build.ps1
```

On Linux/macOS, run `./build.sh`.

To compile and run tests without replacing a currently running desktop JAR:

```powershell
.\build.ps1 -TestsOnly
```

Use `.\build.ps1 -NoClean` to package a new version while an older desktop JAR
is still open on Windows.

## Run the desktop application

Windows:

```powershell
.\run-desktop.ps1
```

Linux/macOS:

```bash
./run-desktop.sh
```

Or run the packaged application directly:

```text
java -jar apps/swing-desktop/target/swing-desktop-0.2.0-SNAPSHOT-all.jar
```

Enter one GitHub HTTPS URL per line, add them to the queue, choose clone and
output folders, then start. Each repository produces:

Alternatively, choose **Search GitHub...** to search by keywords, language,
owner/organization, topic, license, stars, forks, size, activity dates, fork
state, and archive state. Search results can be selected individually, all at
once, or inverted before adding them to the ingestion queue. Authentication is
optional for public repositories; access tokens are not persisted. Results can
be browsed in 25, 50, or 100-row pages without losing selections, and the detail
pane shows URLs, branch, size, activity, license, and repository state. The UI
also explains GitHub's 100-per-page and 1,000-result limits and reports partial
results and the remaining search quota.

```text
analysis-output/<owner>__<repository>/extraction.json
analysis-output/<owner>__<repository>/verification/verification-report.json
analysis-output/<owner>__<repository>/verification/verification-report.csv
```

Enable **Verify extracted model with AlloyInEcore**, select any compatible
`.recore` file, and start the queue. The metamodel is parsed afresh on every
run, so invariants can be changed without rebuilding the desktop. Desktop runs
use strict conformance rather than AlloyInEcore's partial-model completion mode.
The built-in
Spoon mapping profile expects the Java structural elements `Root`, `Class`,
`Method`, and `Attribute`; a structurally different metamodel requires its own
instance mapper, while its constraints may be edited freely.

Repeated runs are incremental: Spoon output is reused only when the repository
revision, Java options, and Java-source content fingerprint still match.
Verification is reused only when both that extraction fingerprint and the full
metamodel content match. Run `VerificationEnvironment/setup.ps1` once to cache
the verifier classpath; subsequent solver launches bypass Maven.

By default only standard `src/main/java` roots are analyzed. Test sources can
be included explicitly from the UI.

## Existing code

The AlloyInEcore engine is bundled in `modules/verification-cli/lib/`. Application
code reaches it only through `verification-integration`, keeping those dependencies
out of the Java 17 desktop runtime.

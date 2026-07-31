# Metamodel Conformance Pipeline

A pipeline for verifying structural type models against formally specified structural conditions. The pipeline ingests object oriented repositories, extracts structural type models, maps them onto a uniform metamodel, and checks conformance against the conditions.

## Quick Links

- [Pipeline architecture](docs/architecture.md)
- [Batch evaluation](docs/experiment.md)
- [Verification engine](docs/verification.md)
- [Extraction backends](docs/extraction.md)
- [JSON to AIE mapper](docs/mapper.md)

## What This Pipeline Does

The pipeline takes a set of repositories (or a single repository) and a formally specified structural metamodel, and answers: does the structure of the code conform to the conditions defined over that metamodel? It supports Java, Python, and C++.

For each repository it produces a verification report recording which conditions were satisfied (SAT) and which were violated (UNSAT), together with per violation descriptions.

## Structure

| Directory | Purpose |
|-----------|---------|
| `modules/` | Software modules (ingestion, extraction, verification, desktop UI) |
| `analysis-output/` | Extracted type models and verification reports per repository |
| `docs/` | Detailed documentation |
| `apps/swing-desktop/` | Swing-based graphical user interface |

## Quick Start

```bash
# Build all modules
./build.ps1   # Windows
./build.sh    # Linux/macOS

# Run the desktop application
./run-desktop.ps1

# Run the pipeline on a single repository
./pipeline.ps1 --repo https://github.com/user/repo --language java
```

See [docs/experiment.md](docs/experiment.md) for batch evaluation over a set of repositories.

## Prerequisites

- Java Development Kit 17+
- Apache Maven 3.9+
- Python 3.10+ (extraction backends for Python and C++)

## License

MIT — see [LICENSE](LICENSE).

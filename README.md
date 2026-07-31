# Metamodel Conformance Pipeline

A pipeline for evaluating the empirical correspondence of structural conditions against production object oriented codebases. The pipeline ingests repositories, extracts structural type models, maps them to a uniform metamodel, and verifies conformance against formally specified structural conditions.

## Quick Links

- [Pipeline architecture](docs/architecture.md)
- [Experimental design](docs/experiment.md)
- [Verification engine](docs/verification.md)
- [Extraction backends](docs/extraction.md)
- [JSON to AIE mapper](docs/mapper.md)

## What This Pipeline Does

The pipeline addresses a methodological gap in structural formalisation: formal verification can establish that a set of conditions is internally consistent, but it cannot determine whether those conditions correspond to the structure of production code. This pipeline measures that correspondence.

It processes 224 open source repositories across Java, Python, and C++, applying nine structural conditions synthesised from six formalisation traditions. For each repository it produces a verification report recording which conditions were satisfied (SAT) and which were violated (UNSAT), together with per violation descriptions.

## Structure

| Directory | Purpose |
|-----------|---------|
| `modules/` | Software modules (ingestion, extraction, verification, desktop UI) |
| `experiment/` | Experiment phases (exploration, normalisation, selection, verification, analysis) |
| `analysis-output/` | Extracted type models and verification reports per repository |
| `analysis-scripts/` | Standalone analysis and figure-generation scripts |
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

# Batch verify the experimental corpus
cd experiment/04_verification
python batch_v2.py --lang Java
```

## Prerequisites

- Java Development Kit 17+
- Apache Maven 3.9+
- Python 3.10+ (for experiment scripts and figures)

## License

MIT — see [LICENSE](LICENSE).

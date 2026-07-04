# Standalone Verifier CLI

AlloyInEcore-based structural invariant checker for Java source code models.

## Requirements

- Java 8+ runtime
- Maven 3.6+
- AlloyInEcore JARs in `lib/` (included in this module)

## Usage

```powershell
# Setup
.\setup.ps1

# Run verification
.\run.ps1 -r .\src\main\resources\ClassHierarchies.recore -i extraction.json -o output --strict --details --report output\report.json --csv output\report.csv
```

For Linux:
```bash
./setup.sh
./run.sh -r src/main/resources/ClassHierarchies.recore -i extraction.json -o output --strict --details --report output/report.json --csv output/report.csv
```

## Arguments

| Argument       | Description                                    |
|----------------|------------------------------------------------|
| `-r <file>`    | AlloyInEcore .recore metamodel (required)      |
| `-i <file>`    | Spoon extraction JSON to map and verify        |
| `-o <dir>`     | Output directory                               |
| `--strict`     | Strict conformance mode                        |
| `--details`    | Show detailed violation information            |
| `--report`     | JSON report output path                        |
| `--csv`        | CSV report output path                         |

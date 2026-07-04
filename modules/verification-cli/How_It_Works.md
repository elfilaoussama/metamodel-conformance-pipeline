# How the Verifier Works

## Overview

The verification pipeline takes a Spoon-extracted Java model (JSON) and checks it
against structural invariants defined in an AlloyInEcore `.recore` metamodel.

## Pipeline

1. **Mapping**: `JsonToAieMapper` converts the Spoon JSON format into an AlloyInEcore
   `.aie` instance file. This creates a synthetic `Root` container with classes,
   methods, and fields with proper parent references and visibility mapping.

2. **Solving**: `InvariantChecker` builds a Kodkod `Universe` and `Bounds` from the
   mapped instance, then runs SAT solving (SAT4J fallback, MiniSatProver preferred).

3. **Reporting**: Results are written as JSON and CSV reports. If the instance is UNSAT,
   violations are extracted via UNSAT core or deletion-based MUS algorithm.

## Invariants

The default `ClassHierarchies.recore` defines invariants such as:
- No cyclic inheritance
- No duplicate type names
- Abstract methods only in abstract classes
- Interface methods are abstract

## MUS Algorithm

When UNSAT is detected and native prover libraries are unavailable, the checker falls
back to a deletion-based Minimal Unsatisfiable Subset extraction to identify the
smallest set of conflicting constraints.

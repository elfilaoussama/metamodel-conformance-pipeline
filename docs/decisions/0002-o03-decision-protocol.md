# ADR 0002: O-03 decision protocol

Status: accepted

## Property

O-03 holds iff the observed inheritance relation is acyclic:

```alloy
no c: Classifier | c in c.^parent
```

## Exactness

The encoder declares exactly one Alloy atom per observed classifier and defines
`parent` as exactly the observed edges. The command scope is exactly the number
of observed classifiers. Alloy is therefore checking the repository instance,
not searching for an unrelated instance that merely fits a loose bound.

## Final decision table

| Preconditions | Alloy witness | Decision |
|---|---:|---|
| Complete evidence | no | `CONFORMANT` |
| Complete evidence | yes | `NON_CONFORMANT` |
| Unresolved parent, parse failure, solver failure, unsupported schema, or resource failure | n/a | `INDETERMINATE` |

A non-conformance result includes the stable IDs and source locations of a
cycle reconstructed from the observed graph. The Alloy solver is the decision
oracle; graph traversal is used only to produce an understandable witness.

## Verification capsule

The final capsule binds the decision to SHA-256 digests of the source set,
`observation.xmi`, and generated Alloy model, plus schema/tool versions and the
command name. `verify-capsule` recomputes those artifact digests in a fresh
process. Any missing or modified artifact invalidates the capsule.

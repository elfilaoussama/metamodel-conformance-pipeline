# Verification Engine

## Overview

The `InvariantChecker` (`modules/verification-cli/`) evaluates structural conditions against extracted type models. It reads an AIE instance file and checks twelve conditions through dedicated Java predicates that mirror the Alloy facts of the formal metamodel.

## Entry Points

| Class | Purpose |
|-------|---------|
| `InvariantChecker` | Core checker: parses .aie, runs all checks, returns violation report |
| `BatchRunner` | Minimal runner: maps JSON to AIE, invokes checker, writes JSON report |
| `ConditionAudit` | Audit tool: verifies every detection path fires against counterexamples |
| `Main` | Full pipeline: parses .recore, maps JSON, invokes checker (requires EMF) |

## Checked Conditions

The checker evaluates 11 invariants, each encoded as a dedicated check method:

| Method | Invariant | What It Checks |
|--------|-----------|----------------|
| `checkNoDuplicateTypeNames` | IdentifierIntegrity | No duplicate classifier names |
| `checkIdUniqueness` | IdentifierIntegrity | Unique cid, mid, aid across atoms |
| `checkExclusiveDeclarationOwnership` | ExclusiveDeclarationOwnership | Each method/attribute has exactly one owner |
| `checkNoCyclicInheritance` | AcyclicGeneralization | No cycles in `parents` relation |
| `checkInheritedMemberDerivation` | InheritedMemberDerivation | Non inheritable members not in inherited sets |
| `checkLocalInheritedDisjointness` | LocalInheritedSeparation | No member in both local and inherited |
| `checkImplementationBinding` | ImplementationBindingPolicy | Bindings valid, no orphans, non abstract methods have bodies |
| `checkUnresolvedMethods` | AbstractionPolicy | Non abstract classifiers have no unresolved methods |
| `checkAbstractMethodInAbstractClass` | AbstractionPolicy | Non abstract classifiers contain no abstract methods |
| `checkNoStaticAbstractMethod` | StaticMethodPolicy | No method is both static and abstract |
| `checkLocalMethodNamespace` | LocalMethodNamespace | No duplicate method keys in same classifier |
| `checkInheritedConflictPolicy` | InheritedConflictPolicy | No conflicting inherited methods or attributes |
| `checkOverrideDiscipline` | OverridePolicy | Overriding return types must match |

## Condition Audit

The `ConditionAudit` class tests every check method against deliberately violating `.aie` counterexamples in `test_conditions/`. Each counterexample triggers exactly one invariant. The audit confirms all detection paths are alive before empirical testing begins.

## AIE Format

The AIE instance file uses a flat atom-and-relation model:

```
Root = {
  classifiers = {Classifier0, Classifier1}
  bodies = { MethodBody0 }
  bindings = { ImplementationBinding0 }

Classifier0 = { cid = "com.example.Foo", name = "com.example.Foo", isAbstract = No }
Method0 = { mid = "M0", memberName = "doStuff", returnType = "void", paramTypes = "_", isInheritable = Yes, scope = Instance, isAbstract = No }
localMethods[Classifier0] = { Method0 }
implementer[ImplementationBinding0] = { Classifier0 }
target[ImplementationBinding0] = { Method0 }
body[ImplementationBinding0] = { MethodBody0 }
}
```

## How to Use

```bash
cd modules/verification-cli
mvn -q compile

# Verify a single repository
java -cp "target/classes;~/.m2/repository/com/google/code/gson/gson/2.10.1/gson-2.10.1.jar" \
     com.verification.BatchRunner extraction.json output/

# Run the condition audit
java -cp "target/classes;~/.m2/repository/com/google/code/gson/gson/2.10.1/gson-2.10.1.jar" \
     com.verification.ConditionAudit
```

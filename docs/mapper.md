# JSON to AIE Mapper

## Overview

The `JsonToAieMapper` (`modules/verification-cli/src/main/java/com/verification/mapper/JsonToAieMapper.java`) converts the uniform TypeModel JSON into Alloy Instance Export (.aie) format. It is language agnostic: all three extractors produce the same JSON structure, and the mapper processes it identically regardless of source language.

## Mapping Rules

| JSON Field | AIE Construct |
|------------|--------------|
| `types[i]` | `Classifier{i}` atom with `cid`, `name`, `isAbstract` fields |
| `types[i].superClass` | `parents[Classifier{i}] = { Classifier{j} }` tuple |
| `types[i].executables[j]` (non constructor) | `Method{k}` atom with `mid`, `memberName`, `returnType`, `paramTypes`, `isInheritable`, `scope`, `isAbstract` fields |
| `types[i].executables[j]` (non constructor, non abstract) | `MethodBody{m}` atom + `ImplementationBinding{m}` with `implementer`, `target`, `body` tuples |
| `types[i].fields[j]` | `Attribute{k}` atom with `aid`, `memberName`, `type`, `isInheritable`, `scope` fields |
| `types[i].executables[*]` | `localMethods[Classifier{i}] = { Method{k}, ... }` tuple |
| `types[i].fields[*]` | `localAttributes[Classifier{i}] = { Attribute{k}, ... }` tuple |
| Method body objects | `bodies = { MethodBody{m}, ... }` in Root compositions |
| Implementation binding objects | `bindings = { ImplementationBinding{m}, ... }` in Root compositions |

## Determinism

The mapper is entirely deterministic: given the same extraction JSON, it produces the same .aie output. All atom identifiers are sequential counters (Classifier0, Method1, etc.) keyed by position in the JSON arrays. This determinism is essential for reproducibility of the empirical results.

## Key Design Choices

- **Constructors are excluded** from `localMethods` tuples and never receive `ImplementationBinding` entries
- **`isInheritable`** maps from source language visibility: `private` → `No`, everything else → `Yes`
- **`scope`** maps from `staticExecutable` / `static` field: `true` → `Static`, `false` → `Instance`
- **`paramTypes`** is a flat comma separated string of parameter type names (e.g., `const unsigned char, uint64_t`). This string is later sorted alphabetically by the invariant checker for method key comparison
- **`parents`** captures single superclass for Java and C++, and all bases for Python (supporting the metamodel's multiple inheritance model)
- **Enum, interface, and record kinds** from source languages are collapsed into the uniform `Classifier` atom

## How to Use

```bash
cd modules/verification-cli
mvn -q compile

java -cp "target/classes;~/.m2/repository/com/google/code/gson/gson/2.10.1/gson-2.10.1.jar" \
     com.verification.mapper.JsonToAieMapper extraction.json MappedInstance.aie
```

## Error Handling

If the extraction JSON contains no types (empty `types` array), the mapper writes an empty .aie file containing only `Root = { classifiers = {} }`. The invariant checker treats empty models as trivially SAT.

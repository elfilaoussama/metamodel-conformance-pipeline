# Pipeline Architecture

## Overview

The pipeline transforms a set of GitHub repository URLs into structured verification reports through four sequential stages.

```
GitHub Repository
       |
  [1. Ingestion]  -- JGit shallow clone
       |
  [2. Extraction] -- Spoon / Python AST / Clang
       |
  [3. Mapping]    -- JsonToAieMapper
       |
  [4. Verification] -- InvariantChecker
       |
  Verification Report (JSON)
```

## Stage 1: Ingestion

Each repository URL is resolved and cloned via JGit (shallow, depth 1). The ingestion module is defined in `modules/github-ingestion/` and implements `RepositoryIngestionService`. Cloned repositories are stored in `workspace/repositories/`.

## Stage 2: Extraction

Each cloned repository is dispatched to a language specific extractor:

| Language | Extractor | Module |
|----------|-----------|--------|
| Java | Spoon (no classpath mode) | `modules/spoon-extraction/` |
| Python | Built-in `ast` module | `modules/python-extraction/` |
| C++ | libclang (Clang) | `modules/cpp-extraction/` |

Every extractor produces the same TypeModel JSON record: qualified name, kind, superclass, fields, methods, constructors, and modifiers. The JSON schema is uniform across languages.

## Stage 3: Mapping

The `JsonToAieMapper` converts TypeModel JSON into Alloy Instance Export (.aie) format. See [Mapper Documentation](mapper.md) for details.

## Stage 4: Verification

The `InvariantChecker` reads the .aie instance and evaluates each structural condition. See [Verification Engine](verification.md) for details.

## Data Flow

```
extraction.json  -->  JsonToAieMapper  -->  MappedInstance.aie  -->  InvariantChecker  -->  verification-report.json
```

## Cache System

The pipeline implements extraction and verification caches using `analysis-cache.properties` files. Cache keys are SHA-256 fingerprints of source code, options, and the metamodel file. Reusing matching entries skips redundant clone, extraction, and verification steps.

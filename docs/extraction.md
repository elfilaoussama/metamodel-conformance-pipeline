# Extraction Backends

## Overview

Each language uses a dedicated extractor that produces a uniform TypeModel JSON record. The extractors live in `modules/<lang>-extraction/`.

## Java (Spoon)

- **Module:** `modules/spoon-extraction/`
- **Framework:** [Spoon](https://fr.inria.fr/gforge/spoon/) v11.3.0
- **Mode:** No classpath (tolerates missing dependencies)
- **Output:** Fully qualified type names, kind (class, interface, enum, record), fields (name, type, visibility, static), executables (name, parameter types, return type, visibility, static, abstract), superclass, implemented interfaces, source line numbers

## Python (ast)

- **Module:** `modules/python-extraction/`
- **Framework:** Python built-in `ast` module
- **Script:** `src/main/resources/extract_python.py`
- **Output:** Qualified names (module path), kinds (class), fields from class body `Assign` nodes, methods from `FunctionDef` with return annotations, bases from class definition, `ABCMeta` metaclass detection for abstractness

## C++ (libclang)

- **Module:** `modules/cpp-extraction/`
- **Framework:** libclang (Clang C API)
- **Script:** `src/main/resources/extract_cpp.py`
- **Output:** Qualified names (namespace path), kinds (class, struct, enum — collapsed to Classifier), fields from `FieldDecl`, methods from `CXXMethodDecl` (constructors excluded), parameter types from parameter declarations, base specifiers for `parents` relation, pure virtual detection for abstractness
- **Limitations:** Processes header files and captures method declarations from system headers transitively included through repository headers

## Extraction JSON Schema

All extractors produce the same JSON schema:

```json
{
  "name": "owner__repo",
  "types": [
    {
      "qualifiedName": "com.example.Foo",
      "kind": "CLASS",
      "superClass": "com.example.Bar",
      "interfaces": ["java.io.Serializable"],
      "abstractType": false,
      "fields": [
        {
          "name": "value",
          "type": "int",
          "visibility": "PRIVATE",
          "static": false
        }
      ],
      "executables": [
        {
          "name": "compute",
          "returnType": "int",
          "visibility": "PUBLIC",
          "static": false,
          "abstractExecutable": false,
          "constructor": false,
          "parameters": [
            {"name": "input", "type": "java.lang.String"}
          ],
          "line": 42
        }
      ]
    }
  ]
}
```

## Extraction Cache

Extraction results are cached via `analysis-cache.properties` using SHA-256 fingerprints of the source code, compliance level, and extraction options. Cache hits skip both cloning and model building.

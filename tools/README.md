# Execution tooling

This directory contains the runtime/provider tooling formerly under `scripts/`.
See the [source map](../documentation/reference/source-map.md) and
[reproduction guide](../documentation/guides/reproduce.md) for the scientific
contracts.

- `resolve-*-dependencies.sh`: declared dependency/build corridors.
- `maven-frames/`: Java compiler-frame and retained-input resources. Eight of
  these files are packaged by the engine into `java-native-build`.
- `run-analysis-batch.py`, `batch/`: supervised sequential execution.
- `maven-cache/`: identity-aware Maven resolution support.
- Repository/corridor runners and input-bundle tools preserve their scoped
  attempt, reconstruction and input-identity contracts.

Provider execution may require Bash, Python, native compilers and the qualified
container environment. These requirements are distinct from the Java web server.
Research-only tests, performance probes and generated evidence are maintained
separately and are excluded from this runtime distribution. Historical command
lines referring to `scripts/` retain the paths of their original experiment.

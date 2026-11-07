# Declared compiler admission (ordered-v3)

Provider contexts now carry one `compiler` row per ordered-v3 translation unit:
three JSON fields, `executableSha256`, `versionSha256` and `target`. The version
digest covers the actual complete version stdout. The observer checks all three
against the executable it will consume before invoking source AST extraction.
Missing, repeated, malformed or mismatching attestations fail closed.

The resolver captures identity inside the same image ID used for CMake, resolving
a mutable tag once. The converter verifies that each database invocation uses
the captured compiler command, preserves the existing ordered operations, and
publishes only after whole-database validation. It accepts isolated provider
paths or explicitly supplied native source-root paths, rejecting escaping and
unavailable inputs. Native conversion is suitable for trusted controlled inputs;
arbitrary project configuration still uses the isolated provider.

Legacy contexts remain supported as host projections; they provide no compiler
compatibility attestation. Matching compiler bytes/version/target alone does not
certify shared libraries, platform headers, generated sources, dependency closure
or target/support population completeness. Provider receipts remain NOT_CERTIFIED
project measurements until those separate obligations are satisfied. W04 remains
open. P0 conditions, model schema, encoding and Alloy evaluation are unchanged.

The motivating controlled source selected own(int) under host Clang 14.0.0 and
own(double) under provider Clang 14.0.6 because of __clang_patchlevel__. This is a
source-fidelity difference, not evidence that a P0 condition changed truth value.

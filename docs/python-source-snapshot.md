# Python parser input snapshot — adapter 0.9.0

The observer captures its selected Python files in a private native temporary
directory, preserving relative names. Source-unit digests are computed from the
captured files, and the existing encoding-aware AST bridge consumes those files.
This prevents original-file changes during parsing from creating different source
facts paired with unchanged recorded hashes, including edits restored before return.

Before publishing, the observer checks the original selected file population and
bytes against the captured identity. Permanent changes, deletion or additions
reject observation. Temporary inputs are removed on success and failure. No
arbitrary module executes; only the existing AST bridge consumes source text.

This is a per-observation captured population, not an atomic filesystem/Git revision
snapshot or independent reproduction bundle. Host/native interpreter libraries,
declared distributions and Java/C++ snapshot obligations remain separate W04/W12
work. The fixed formal profile, schema, encoding and Python binding/descriptor
mapping are unchanged. Temporary copy/hash/check cost is part of observation cost;
no cross-attempt cache or speedup claim is introduced.

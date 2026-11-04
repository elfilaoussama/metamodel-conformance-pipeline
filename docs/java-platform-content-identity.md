# Java platform content identity v2

Java dependency-aware adapter 1.17.0 fingerprints the selected platform's regular
release metadata, its lib/modules image, the actual running compiler's lib/modules
image, ct.sym when --release is selected, and effective release/preview semantics.
The versioned aggregate digest is represented as platform/<digest>/content-v2.
Identical selected/compiler images are hashed once in a capture; there is no
cross-attempt digest cache. Different images retain their separate roles.

The supported modular JDK profile requires regular non-symlink image files.
Metadata without an available image fails capture. This closes the demonstrated
identity collision for changed module-image bytes behind unchanged release
metadata. The integrity fixtures deliberately use small byte files; they do not
represent valid JDKs or establish a source semantic result.

The fixed P0, canonical schema and formal evaluator are unchanged. Legacy capsule
replay uses its recorded provenance interpretation rather than fresh extraction.
This stronger identity does not certify native JVM libraries, arbitrary provider
compiler compatibility, generated inputs or all target/support population roles.
W04 remains open, and module hashing cost is part of observation cost.

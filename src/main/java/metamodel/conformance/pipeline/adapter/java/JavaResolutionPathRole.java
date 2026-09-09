package metamodel.conformance.pipeline.adapter.java;

/** Semantic role of an ordered javac resolution path. */
enum JavaResolutionPathRole {
    CLASS_PATH,
    MODULE_PATH,
    UPGRADE_MODULE_PATH,
    PROCESSOR_PATH,
    PLATFORM_PATH,
    PATCH_MODULE
}

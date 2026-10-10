package metamodel.conformance.pipeline.adapter;

import metamodel.conformance.pipeline.adapter.cpp.ClangCppObserver;
import metamodel.conformance.pipeline.adapter.cpp.CppDependencyInputs;
import metamodel.conformance.pipeline.adapter.java.JavaDependencyAwareSourceObserver;
import metamodel.conformance.pipeline.adapter.java.JavaDependencyInputs;
import metamodel.conformance.pipeline.adapter.python.PythonAstObserver;
import metamodel.conformance.pipeline.adapter.python.PythonDependencyInputs;
import metamodel.conformance.pipeline.model.Language;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Selects a source-language observer without coupling pipeline orchestration to one frontend. */
public final class SourceObserverFactory {
    private SourceObserverFactory() {
    }

    public static Language parseLanguage(String value) {
        if (value == null || value.isBlank()) {
            return Language.JAVA;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "java" -> Language.JAVA;
            case "python" -> Language.PYTHON;
            case "cpp" -> Language.CPP;
            default -> throw new IllegalArgumentException("unsupported source language: " + value);
        };
    }

    public static SourceObserver create(Language language, List<Path> dependencyArchives) {
        return create(language, JavaDependencyInputs.global(dependencyArchives));
    }

    public static SourceObserver create(Language language, JavaDependencyInputs dependencyInputs) {
        Objects.requireNonNull(language, "language");
        JavaDependencyInputs dependencies = dependencyInputs == null
                ? JavaDependencyInputs.none() : dependencyInputs;
        return switch (language) {
            case JAVA -> new JavaDependencyAwareSourceObserver(dependencies);
            case PYTHON -> {
                rejectJavaDependencies(dependencies, "Python");
                yield new PythonAstObserver();
            }
            case CPP -> {
                rejectJavaDependencies(dependencies, "C++");
                yield new ClangCppObserver();
            }
            case JAVA_ARCHIVE, JAVA_PLATFORM -> throw new IllegalArgumentException(
                    language + " is evidence, not a source language");
        };
    }

    /**
     * Language-specific manifest selection: Java reads the Java resolver
     * manifest (or archive inputs when no manifest was declared), C++ reads the
     * CMake resolver manifest, and a language without dependency resolution
     * rejects the input.
     */
    public static SourceObserver create(
            Language language, Path dependencyManifest, JavaDependencyInputs archiveInputs) throws IOException {
        Objects.requireNonNull(language, "language");
        JavaDependencyInputs javaInputs = language == Language.JAVA && dependencyManifest != null
                ? JavaDependencyInputs.fromManifest(dependencyManifest)
                : archiveInputs == null ? JavaDependencyInputs.none() : archiveInputs;
        return switch (language) {
            case JAVA -> new JavaDependencyAwareSourceObserver(javaInputs);
            case PYTHON -> {
                rejectJavaDependencies(javaInputs, "Python");
                yield new PythonAstObserver(
                        dependencyManifest == null
                                ? PythonDependencyInputs.none()
                                : PythonDependencyInputs.fromManifest(dependencyManifest));
            }
            case CPP -> {
                rejectJavaDependencies(javaInputs, "C++");
                yield new ClangCppObserver(
                        dependencyManifest == null
                                ? CppDependencyInputs.none()
                                : CppDependencyInputs.fromManifest(dependencyManifest));
            }
            case JAVA_ARCHIVE, JAVA_PLATFORM -> throw new IllegalArgumentException(
                    language + " is evidence, not a source language");
        };
    }

    private static void rejectJavaDependencies(JavaDependencyInputs dependencies, String language) {
        if (!dependencies.isEmpty()) {
            throw new IllegalArgumentException("Java dependency inputs are not supported for " + language + " sources");
        }
    }
}

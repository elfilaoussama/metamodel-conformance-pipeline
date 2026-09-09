package metamodel.conformance.pipeline.adapter;

import metamodel.conformance.pipeline.adapter.cpp.ClangCppObserver;
import metamodel.conformance.pipeline.adapter.java.JavaDependencyAwareSourceObserver;
import metamodel.conformance.pipeline.adapter.java.JavaDependencyInputs;
import metamodel.conformance.pipeline.adapter.python.PythonAstObserver;
import metamodel.conformance.pipeline.model.Language;

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
                yield new Schema12SourceObserver(new PythonAstObserver());
            }
            case CPP -> {
                rejectJavaDependencies(dependencies, "C++");
                yield new Schema12SourceObserver(new ClangCppObserver());
            }
            case JAVA_ARCHIVE -> throw new IllegalArgumentException(
                    "JAVA_ARCHIVE is dependency evidence, not a source language");
        };
    }

    private static void rejectJavaDependencies(JavaDependencyInputs dependencies, String language) {
        if (!dependencies.isEmpty()) {
            throw new IllegalArgumentException("Java dependency inputs are not supported for " + language + " sources");
        }
    }
}

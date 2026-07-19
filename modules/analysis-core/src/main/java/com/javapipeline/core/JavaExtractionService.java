package com.javapipeline.core;

import com.javapipeline.core.model.ExtractionResult;

import java.nio.file.Path;

public interface JavaExtractionService {
    ExtractionResult extract(
            String projectName,
            Path repository,
            ExtractionOptions options,
            ProgressListener progress,
            CancellationToken cancellation
    ) throws JavaExtractionException;

    default Language getLanguage() {
        return Language.JAVA;
    }
}

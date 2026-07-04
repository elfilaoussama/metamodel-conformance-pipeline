package com.javapipeline.core;

import java.nio.file.Path;

public interface RepositoryIngestionService {
    IngestedRepository ingest(
            RepositoryRequest request,
            Path workspace,
            ProgressListener progress,
            CancellationToken cancellation
    ) throws RepositoryIngestionException;
}

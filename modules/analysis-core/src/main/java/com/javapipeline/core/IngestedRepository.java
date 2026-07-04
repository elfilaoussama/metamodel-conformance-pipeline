package com.javapipeline.core;

import java.nio.file.Path;
import java.util.Objects;

public record IngestedRepository(RepositoryRequest request, Path directory, String revision, boolean reused) {
    public IngestedRepository {
        Objects.requireNonNull(request, "request");
        directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        revision = Objects.requireNonNullElse(revision, "");
    }
}

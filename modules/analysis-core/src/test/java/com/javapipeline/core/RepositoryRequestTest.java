package com.javapipeline.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RepositoryRequestTest {
    @Test
    void normalizesGitHubUrl() {
        RepositoryRequest request = RepositoryRequest.fromGitHubUrl(
                "https://github.com/MaxMind/MaxMind-DB-Reader-java.git", 1, ExistingRepositoryPolicy.REUSE);
        assertEquals("MaxMind/MaxMind-DB-Reader-java", request.coordinate());
        assertEquals("https://github.com/MaxMind/MaxMind-DB-Reader-java.git", request.remoteUri().toString());
    }

    @Test
    void rejectsNonGitHubHosts() {
        assertThrows(IllegalArgumentException.class, () -> RepositoryRequest.fromGitHubUrl(
                "https://example.com/acme/demo", 1, ExistingRepositoryPolicy.REUSE));
    }
}

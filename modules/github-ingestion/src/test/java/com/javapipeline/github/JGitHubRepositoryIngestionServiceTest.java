package com.javapipeline.github;

import com.javapipeline.core.*;
import org.eclipse.jgit.api.Git;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class JGitHubRepositoryIngestionServiceTest {
    @TempDir Path workspace;

    @Test
    void reusesAnExistingCloneWithoutNetworkAccess() throws Exception {
        Path destination = workspace.resolve("acme__demo");
        try (Git git = Git.init().setDirectory(destination.toFile()).call()) {
            Files.writeString(destination.resolve("README.md"), "fixture");
            git.add().addFilepattern("README.md").call();
            git.commit().setMessage("initial").setAuthor("Test", "test@example.com")
                    .setCommitter("Test", "test@example.com").call();
        }

        RepositoryRequest request = new RepositoryRequest(
                URI.create("https://github.com/acme/demo.git"), "acme", "demo", "", 1,
                ExistingRepositoryPolicy.REUSE);
        var result = new JGitHubRepositoryIngestionService().ingest(
                request, workspace, ProgressListener.NONE, CancellationToken.NONE);

        assertTrue(result.reused());
        assertEquals(destination.toAbsolutePath().normalize(), result.directory());
        assertEquals(40, result.revision().length());
    }

    @Test
    void failPolicyDoesNotOverwriteExistingDirectories() throws Exception {
        Files.createDirectories(workspace.resolve("acme__demo"));
        RepositoryRequest request = new RepositoryRequest(
                URI.create("https://github.com/acme/demo.git"), "acme", "demo", "", 1,
                ExistingRepositoryPolicy.FAIL);
        assertThrows(RepositoryIngestionException.class, () ->
                new JGitHubRepositoryIngestionService().ingest(
                        request, workspace, ProgressListener.NONE, CancellationToken.NONE));
    }
}

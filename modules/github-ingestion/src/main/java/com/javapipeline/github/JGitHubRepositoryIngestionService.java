package com.javapipeline.github;

import com.javapipeline.core.*;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.ProgressMonitor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

public final class JGitHubRepositoryIngestionService implements RepositoryIngestionService {
    @Override
    public IngestedRepository ingest(
            RepositoryRequest request,
            Path workspace,
            ProgressListener progress,
            CancellationToken cancellation
    ) throws RepositoryIngestionException {
        ProgressListener listener = progress == null ? ProgressListener.NONE : progress;
        CancellationToken token = cancellation == null ? CancellationToken.NONE : cancellation;
        Path root = workspace.toAbsolutePath().normalize();
        Path destination = root.resolve(request.owner() + "__" + request.name());
        boolean destinationCreatedByThisCall = false;

        try {
            Files.createDirectories(root);
            token.throwIfCancellationRequested();
            if (Files.exists(destination)) {
                return reuseOrFail(request, destination, listener);
            }

            listener.onProgress(ProgressEvent.indeterminate(
                    ProgressEvent.Stage.CLONING, "Cloning " + request.coordinate()));
            destinationCreatedByThisCall = true;
            var clone = Git.cloneRepository()
                    .setURI(request.remoteUri().toString())
                    .setDirectory(destination.toFile())
                    .setDepth(request.cloneDepth())
                    .setCloneAllBranches(false)
                    .setProgressMonitor(new ListenerProgressMonitor(listener, token));
            if (!request.branch().isBlank()) clone.setBranch(request.branch());

            try (Git git = clone.call()) {
                String revision = git.getRepository().resolve("HEAD").name();
                return new IngestedRepository(request, destination, revision, false);
            }
        } catch (Exception ex) {
            if (destinationCreatedByThisCall && Files.exists(destination)) {
                try { deleteTree(destination); } catch (IOException cleanup) { ex.addSuppressed(cleanup); }
            }
            if (ex instanceof RepositoryIngestionException ingestion) throw ingestion;
            throw new RepositoryIngestionException("Could not ingest " + request.coordinate(), ex);
        }
    }

    private IngestedRepository reuseOrFail(
            RepositoryRequest request, Path destination, ProgressListener listener
    ) throws RepositoryIngestionException {
        if (request.existingPolicy() == ExistingRepositoryPolicy.FAIL) {
            throw new RepositoryIngestionException("Destination already exists: " + destination);
        }
        if (!Files.isDirectory(destination.resolve(".git"))) {
            throw new RepositoryIngestionException("Existing destination is not a Git repository: " + destination);
        }
        try (Git git = Git.open(destination.toFile())) {
            String revision = git.getRepository().resolve("HEAD").name();
            listener.onProgress(ProgressEvent.indeterminate(
                    ProgressEvent.Stage.PREPARING, "Reusing " + request.coordinate()));
            return new IngestedRepository(request, destination, revision, true);
        } catch (IOException ex) {
            throw new RepositoryIngestionException("Could not open existing repository: " + destination, ex);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private static final class ListenerProgressMonitor implements ProgressMonitor {
        private final ProgressListener listener;
        private final CancellationToken cancellation;
        private String task = "Cloning";
        private int total;
        private int completed;

        private ListenerProgressMonitor(ProgressListener listener, CancellationToken cancellation) {
            this.listener = listener;
            this.cancellation = cancellation;
        }

        @Override public void start(int totalTasks) { }

        @Override
        public void beginTask(String title, int totalWork) {
            task = title;
            total = Math.max(0, totalWork);
            completed = 0;
            publish();
        }

        @Override
        public void update(int completedWork) {
            completed += completedWork;
            publish();
        }

        @Override public void endTask() { publish(); }
        @Override public boolean isCancelled() { return cancellation.isCancellationRequested(); }
        @Override public void showDuration(boolean enabled) { }

        private void publish() {
            listener.onProgress(new ProgressEvent(ProgressEvent.Stage.CLONING, task, completed, total));
        }
    }
}

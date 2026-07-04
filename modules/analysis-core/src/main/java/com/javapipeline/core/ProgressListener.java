package com.javapipeline.core;

@FunctionalInterface
public interface ProgressListener {
    ProgressListener NONE = event -> { };

    void onProgress(ProgressEvent event);
}

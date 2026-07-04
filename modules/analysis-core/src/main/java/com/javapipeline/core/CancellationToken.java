package com.javapipeline.core;

import java.util.concurrent.CancellationException;

@FunctionalInterface
public interface CancellationToken {
    CancellationToken NONE = () -> false;

    boolean isCancellationRequested();

    default void throwIfCancellationRequested() {
        if (isCancellationRequested()) {
            throw new CancellationException("Operation cancelled");
        }
    }
}

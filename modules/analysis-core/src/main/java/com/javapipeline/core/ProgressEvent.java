package com.javapipeline.core;

public record ProgressEvent(Stage stage, String message, long completed, long total) {
    public enum Stage { PREPARING, SEARCHING, CLONING, DISCOVERING, EXTRACTING, WRITING, VERIFYING, COMPLETED }

    public ProgressEvent {
        message = message == null ? "" : message;
        completed = Math.max(0, completed);
        total = Math.max(0, total);
    }

    public static ProgressEvent indeterminate(Stage stage, String message) {
        return new ProgressEvent(stage, message, 0, 0);
    }
}

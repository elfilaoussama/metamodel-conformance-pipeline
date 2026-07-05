package com.javapipeline.desktop;

final class SwingUtils {
    private SwingUtils() { }

    static String blank(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback == null ? "" : fallback;
        }
        return value;
    }
}

package com.javapipeline.core;

public enum Language {
    JAVA, PYTHON, CPP;

    public static Language fromExtension(String ext) {
        if (ext == null) return null;
        return switch (ext.toLowerCase()) {
            case "java" -> JAVA;
            case "py" -> PYTHON;
            case "cpp", "cc", "cxx", "hpp", "h", "hxx" -> CPP;
            default -> null;
        };
    }
}

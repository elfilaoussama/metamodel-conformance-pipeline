package com.javapipeline.verification;

public class VerificationException extends Exception {
    public VerificationException(String message) { super(message); }
    public VerificationException(String message, Throwable cause) { super(message, cause); }
}

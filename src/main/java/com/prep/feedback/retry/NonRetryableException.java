package com.prep.feedback.retry;

/** Thrown by a classifier when retrying cannot help (e.g. HTTP 400 / invalid input). */
public class NonRetryableException extends RuntimeException {
    public NonRetryableException(String message) { super(message); }
}

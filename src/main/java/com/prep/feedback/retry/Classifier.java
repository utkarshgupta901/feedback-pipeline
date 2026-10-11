package com.prep.feedback.retry;

/** The slow, flaky external call. Implementations may throw anything. */
@FunctionalInterface
public interface Classifier {
    String classify(String text) throws Exception;
}

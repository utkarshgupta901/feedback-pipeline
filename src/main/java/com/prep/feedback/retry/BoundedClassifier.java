package com.prep.feedback.retry;

/**
 * STEP 2c - Decorator that allows at most N concurrent calls to the delegate.
 *
 * Requirements:
 *  - never more than maxConcurrent calls inside delegate.classify(...) at the same time
 *  - extra callers WAIT (block), they don't fail
 *  - a permit must be returned even if the delegate throws
 *  - maxConcurrent < 1 -> IllegalArgumentException
 *
 * Note: the permit should be held only during the call - NOT during backoff sleeps.
 * Why that is automatically true here is a good interview talking point (see LEARNING.md).
 */
public class BoundedClassifier implements Classifier {
    private final Classifier delegate;
    // TODO step 2c: what java.util.concurrent primitive limits concurrency to N?

    public BoundedClassifier(Classifier delegate, int maxConcurrent) {
        this.delegate = delegate;
        // TODO step 2c
        throw new UnsupportedOperationException("TODO step 2c");
    }

    @Override
    public String classify(String text) throws Exception {
        throw new UnsupportedOperationException("TODO step 2c");
    }
}

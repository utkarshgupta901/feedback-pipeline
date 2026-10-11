package com.prep.feedback.retry;

import com.prep.feedback.model.Outcome;
import com.prep.feedback.model.PreparedRecord;

import java.util.random.RandomGenerator;

/** Calls the classifier up to K times with backoff; never throws for classifier failures. */
public class RetryingClassifier {
    private final Classifier classifier;
    private final RetryPolicy policy;
    private final Sleeper sleeper;
    private final RandomGenerator rnd;

    public RetryingClassifier(Classifier classifier, RetryPolicy policy, Sleeper sleeper, RandomGenerator rnd) {
        this.classifier = classifier;
        this.policy = policy;
        this.sleeper = sleeper;
        this.rnd = rnd;
    }

    /**
     * Rules:
     * <ul>
     *   <li>Success on attempt i  -> Outcome.Success(key, label, i)</li>
     *   <li>Exception             -> sleep policy.backoff(i) then retry, up to maxAttempts</li>
     *   <li>NonRetryableException -> DeadLetter immediately</li>
     *   <li>All attempts failed   -> DeadLetter(key, maxAttempts, lastError.toString())</li>
     *   <li>No sleep after the final failed attempt.</li>
     *   <li>InterruptedException (from the sleeper OR from the classifier) is NOT a classifier
     *       failure: re-set the interrupt flag and rethrow - we are being shut down.</li>
     *   <li>Errors (OutOfMemoryError, etc.) are not caught.</li>
     * </ul>
     */
    public Outcome run(PreparedRecord record) throws InterruptedException {
        // Order of catch blocks matters: InterruptedException, NonRetryableException, then Exception.
        throw new UnsupportedOperationException("TODO step 2b");
    }
}

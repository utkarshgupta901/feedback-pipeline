package com.prep.feedback.retry;

import java.time.Duration;
import java.util.random.RandomGenerator;

/**
 * @param maxAttempts K - total attempts including the first one (K=1 means no retries)
 * @param baseDelay   delay cap after the first failure
 * @param maxDelay    the cap never grows beyond this
 */
public record RetryPolicy(int maxAttempts, Duration baseDelay, Duration maxDelay) {

    public RetryPolicy {
        // TODO step 2a: validate maxAttempts >= 1 and 0 <= baseDelay <= maxDelay,
        //              else throw IllegalArgumentException.
    }

    /**
     * "Full jitter" exponential backoff (AWS architecture blog).
     * cap  = min(maxDelay, baseDelay * 2^(failedAttempt-1))
     * wait = uniform random in [0, cap]
     *
     * @param failedAttempt 1-based number of the attempt that just failed
     */
    public Duration backoff(int failedAttempt, RandomGenerator rnd) {
        // Watch out: base * 2^30+ overflows a long. Clamp the exponent.
        throw new UnsupportedOperationException("TODO step 2a");
    }
}

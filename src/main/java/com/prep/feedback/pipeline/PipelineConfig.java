package com.prep.feedback.pipeline;

import com.prep.feedback.retry.RetryPolicy;

/**
 * @param maxConcurrentCalls N - hard cap on in-flight classify() calls
 * @param workers            number of worker threads pulling from the fair queue.
 *                           Can be > N so that while some workers sleep in backoff,
 *                           others keep all N permits busy.
 * @param retryPolicy        K attempts + backoff settings
 */
public record PipelineConfig(int maxConcurrentCalls, int workers, RetryPolicy retryPolicy) {
    public PipelineConfig {
        if (maxConcurrentCalls < 1 || workers < 1) throw new IllegalArgumentException("N and workers must be >= 1");
    }
}

package com.prep.feedback.model;

/**
 * A cleaned, de-duplicated record ready for classification.
 *
 * @param original  the raw record (kept for traceability)
 * @param cleanText normalized text that is sent to classify()
 * @param key       stable idempotency key: tenant + hash(normalized text).
 *                  Same input => same key on every run, which is what makes reruns safe.
 */
public record PreparedRecord(FeedbackRecord original, String cleanText, String key) {
    public String tenant() { return original.tenant(); }
}

package com.prep.feedback.support;

import com.prep.feedback.model.FeedbackRecord;

import java.time.Instant;

public final class Records {
    private Records() {}

    /** Shorthand: rec("acme", "z-1", "great app", 100) -> timestamp = epoch second 100. */
    public static FeedbackRecord rec(String tenant, String id, String text, long epochSecond) {
        return new FeedbackRecord("zendesk", id, tenant, text, Instant.ofEpochSecond(epochSecond));
    }
}

package com.prep.feedback.model;

import java.time.Instant;

/** Raw input exactly as it arrives from a source (Zendesk, app store, email...). */
public record FeedbackRecord(String source, String id, String tenant, String text, Instant timestamp) {}

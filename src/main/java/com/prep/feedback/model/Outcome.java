package com.prep.feedback.model;

/** Terminal result of processing one record. Sealed so a switch over it is exhaustive. */
public sealed interface Outcome permits Outcome.Success, Outcome.DeadLetter {
    String key();
    int attempts();

    record Success(String key, String label, int attempts) implements Outcome {}

    record DeadLetter(String key, int attempts, String lastError) implements Outcome {}
}

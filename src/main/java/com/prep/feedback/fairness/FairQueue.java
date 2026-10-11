package com.prep.feedback.fairness;

/**
 * STEP 3 - Thread-safe multi-tenant queue that hands out items round-robin across tenants.
 * Tenant A with 10_000 items and tenant B with 3 items: B's items come out within
 * the first 6 takes, not after A's 10_000.
 *
 * Contract (the tests check all of it):
 *  - within one tenant: FIFO
 *  - across tenants: round-robin; a tenant seen for the first time (or coming back after
 *    its queue drained) joins at the END of the rotation
 *  - take() blocks while empty; returns null once close() was called AND it is empty
 *  - close() wakes up all blocked takers; offer() after close -> IllegalStateException
 *  - safe for many producers and many consumers at once
 *
 * Hint: one ReentrantLock + one Condition, a Map<tenant, ArrayDeque<T>> and an
 * ArrayDeque<String> "ring" of tenants that currently have items.
 */
public class FairQueue<T> {
    // TODO step 3: fields

    public void offer(String tenant, T item) {
        throw new UnsupportedOperationException("TODO step 3");
    }

    public T take() throws InterruptedException {
        throw new UnsupportedOperationException("TODO step 3");
    }

    public void close() {
        throw new UnsupportedOperationException("TODO step 3");
    }

    public int size() {
        throw new UnsupportedOperationException("TODO step 3");
    }
}

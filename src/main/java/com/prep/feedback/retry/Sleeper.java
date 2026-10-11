package com.prep.feedback.retry;

import java.time.Duration;

/** Abstraction over Thread.sleep so tests don't actually wait. */
@FunctionalInterface
public interface Sleeper {
    void sleep(Duration d) throws InterruptedException;

    Sleeper REAL = d -> Thread.sleep(d);
    Sleeper NO_OP = d -> {};
}

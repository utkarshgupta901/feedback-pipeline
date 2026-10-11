package com.prep.feedback;

import com.prep.feedback.model.Outcome;
import com.prep.feedback.model.PreparedRecord;
import com.prep.feedback.retry.BoundedClassifier;
import com.prep.feedback.retry.Classifier;
import com.prep.feedback.retry.RetryPolicy;
import com.prep.feedback.retry.RetryingClassifier;
import com.prep.feedback.retry.Sleeper;
import com.prep.feedback.support.FakeClassifier;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.prep.feedback.support.Records.rec;
import static org.junit.jupiter.api.Assertions.*;

/** STEP 2 - retry with backoff + jitter, dead-letter after K, at most N concurrent calls.  Run: mvn test -Dtest='Step2*' */
class Step2RetryTest {

    private static final RetryPolicy K3 = new RetryPolicy(3, Duration.ofMillis(100), Duration.ofSeconds(2));

    private static PreparedRecord prepared(String text) {
        return new PreparedRecord(rec("acme", "1", text, 1), text, "acme:" + text);
    }

    /** Sleeper that records requested sleeps instead of sleeping. */
    private static final class RecordingSleeper implements Sleeper {
        final List<Duration> sleeps = new ArrayList<>();
        @Override public void sleep(Duration d) { sleeps.add(d); }
    }

    // ---------- RetryPolicy.backoff ----------

    @Test
    void backoff_staysWithinExponentialCap() {
        Random rnd = new Random(42);
        long[] caps = {100, 200, 400, 800, 1600, 2000, 2000}; // base 100ms doubling, capped at 2s
        for (int attempt = 1; attempt <= caps.length; attempt++) {
            long maxSeen = 0;
            for (int i = 0; i < 2_000; i++) {
                long ms = K3.backoff(attempt, rnd).toMillis();
                assertTrue(ms >= 0 && ms <= caps[attempt - 1],
                        "attempt " + attempt + " gave " + ms + "ms, cap is " + caps[attempt - 1]);
                maxSeen = Math.max(maxSeen, ms);
            }
            assertTrue(maxSeen > caps[attempt - 1] / 2, "jitter should spread over [0, cap]; attempt " + attempt);
        }
    }

    @Test
    void backoff_isJittered_notConstant() {
        Random rnd = new Random(7);
        long a = K3.backoff(3, rnd).toMillis(), b = K3.backoff(3, rnd).toMillis(), c = K3.backoff(3, rnd).toMillis();
        assertFalse(a == b && b == c, "three identical delays - where is the jitter?");
    }

    @Test
    void backoff_doesNotOverflowForHugeAttemptNumbers() {
        long ms = K3.backoff(1_000, new Random(1)).toMillis();
        assertTrue(ms >= 0 && ms <= 2_000);
    }

    @Test
    void policy_rejectsInvalidConfig() {
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(0, Duration.ZERO, Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new RetryPolicy(3, Duration.ofSeconds(5), Duration.ofSeconds(1)));
    }

    // ---------- RetryingClassifier ----------

    @Test
    void success_onFirstTry_noSleep() throws Exception {
        RecordingSleeper sleeper = new RecordingSleeper();
        Outcome o = new RetryingClassifier(FakeClassifier.fast(), K3, sleeper, new Random(1)).run(prepared("nice"));
        assertEquals(new Outcome.Success("acme:nice", "label:nice", 1), o);
        assertTrue(sleeper.sleeps.isEmpty());
    }

    @Test
    void flaky_succeedsAfterRetries_sleepingBetweenAttempts() throws Exception {
        FakeClassifier c = FakeClassifier.fast(); // "flaky" fails twice
        RecordingSleeper sleeper = new RecordingSleeper();
        Outcome o = new RetryingClassifier(c, K3, sleeper, new Random(1)).run(prepared("flaky one"));
        assertEquals(new Outcome.Success("acme:flaky one", "label:flaky one", 3), o);
        assertEquals(3, c.callsFor("flaky one"));
        assertEquals(2, sleeper.sleeps.size(), "sleep between attempts 1-2 and 2-3 only");
    }

    @Test
    void alwaysFailing_goesToDeadLetterAfterK_withoutFinalSleep() throws Exception {
        FakeClassifier c = FakeClassifier.fast();
        RecordingSleeper sleeper = new RecordingSleeper();
        Outcome o = new RetryingClassifier(c, K3, sleeper, new Random(1)).run(prepared("always-fail"));
        Outcome.DeadLetter d = assertInstanceOf(Outcome.DeadLetter.class, o);
        assertEquals(3, d.attempts());
        assertTrue(d.lastError().contains("503"), "keep the last error for debugging: " + d.lastError());
        assertEquals(3, c.callsFor("always-fail"));
        assertEquals(2, sleeper.sleeps.size(), "no point sleeping after the last attempt");
    }

    @Test
    void nonRetryable_goesToDeadLetterImmediately() throws Exception {
        FakeClassifier c = FakeClassifier.fast();
        RecordingSleeper sleeper = new RecordingSleeper();
        Outcome o = new RetryingClassifier(c, K3, sleeper, new Random(1)).run(prepared("bad-input"));
        Outcome.DeadLetter d = assertInstanceOf(Outcome.DeadLetter.class, o);
        assertEquals(1, d.attempts());
        assertEquals(1, c.callsFor("bad-input"));
        assertTrue(sleeper.sleeps.isEmpty());
    }

    @Test
    void interruption_isNotTreatedAsAFailure() {
        Sleeper interrupted = d -> { throw new InterruptedException("shutdown"); };
        FakeClassifier c = FakeClassifier.fast();
        RetryingClassifier rc = new RetryingClassifier(c, K3, interrupted, new Random(1));
        assertThrows(InterruptedException.class, () -> rc.run(prepared("always-fail")));
        assertEquals(1, c.callsFor("always-fail"), "must stop retrying once interrupted");

        Classifier interruptedCall = text -> { throw new InterruptedException("shutdown"); };
        RetryingClassifier rc2 = new RetryingClassifier(interruptedCall, K3, Sleeper.NO_OP, new Random(1));
        assertThrows(InterruptedException.class, () -> rc2.run(prepared("x")));
        assertTrue(Thread.interrupted(), "interrupt flag should be restored (this call also clears it)");
    }

    // ---------- BoundedClassifier ----------

    @Test
    void bounded_neverExceedsN_butDoesUseAllN() throws Exception {
        FakeClassifier slow = FakeClassifier.slow(20);
        Classifier bounded = new BoundedClassifier(slow, 3);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<String>> fs = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                String t = "text-" + i;
                fs.add(pool.submit(() -> bounded.classify(t)));
            }
            for (Future<String> f : fs) f.get();
        }
        assertEquals(40, slow.totalCalls());
        assertEquals(3, slow.maxInFlight(), "should hit exactly N concurrent calls");
    }

    @Test
    void bounded_releasesPermitWhenDelegateThrows() {
        Classifier bounded = new BoundedClassifier(FakeClassifier.fast(), 1);
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            for (int i = 0; i < 5; i++) {
                assertThrows(RuntimeException.class, () -> bounded.classify("always-fail"));
            }
        }, "permit leaked: second call blocked forever");
    }
}

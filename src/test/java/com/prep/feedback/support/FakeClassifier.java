package com.prep.feedback.support;

import com.prep.feedback.retry.Classifier;
import com.prep.feedback.retry.NonRetryableException;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test double for the slow, flaky classify() call. Behaviour is driven by the text:
 *   contains "always-fail" -> always throws RuntimeException
 *   contains "bad-input"   -> throws NonRetryableException
 *   contains "flaky"       -> fails the first `flakyFailures` calls for that text, then succeeds
 *   anything else          -> succeeds
 * Successful label = "label:" + text.
 * It records every call (order, per-text counts) and the max number of concurrent calls.
 */
public class FakeClassifier implements Classifier {
    private final long latencyMs;
    private final int flakyFailures;
    private final int crashOnCallNumber; // 0 = never

    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();
    private final AtomicInteger totalCalls = new AtomicInteger();
    private final Map<String, AtomicInteger> callsPerText = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<String> callOrder = new ConcurrentLinkedQueue<>();

    public FakeClassifier(long latencyMs, int flakyFailures, int crashOnCallNumber) {
        this.latencyMs = latencyMs;
        this.flakyFailures = flakyFailures;
        this.crashOnCallNumber = crashOnCallNumber;
    }

    public static FakeClassifier fast() { return new FakeClassifier(0, 2, 0); }

    public static FakeClassifier slow(long latencyMs) { return new FakeClassifier(latencyMs, 2, 0); }

    /** Throws SimulatedCrash (an Error, so nobody catches it) on the given call number. */
    public static FakeClassifier crashingOnCall(int n) { return new FakeClassifier(0, 2, n); }

    @Override
    public String classify(String text) throws Exception {
        int callNo = totalCalls.incrementAndGet();
        callOrder.add(text);
        int n = callsPerText.computeIfAbsent(text, t -> new AtomicInteger()).incrementAndGet();
        int now = inFlight.incrementAndGet();
        maxInFlight.accumulateAndGet(now, Math::max);
        try {
            if (crashOnCallNumber > 0 && callNo == crashOnCallNumber) throw new SimulatedCrash();
            if (latencyMs > 0) Thread.sleep(latencyMs);
            if (text.contains("always-fail")) throw new RuntimeException("classifier 503 for " + text);
            if (text.contains("bad-input")) throw new NonRetryableException("classifier 400 for " + text);
            if (text.contains("flaky") && n <= flakyFailures) throw new RuntimeException("timeout #" + n);
            return "label:" + text;
        } finally {
            inFlight.decrementAndGet();
        }
    }

    public int maxInFlight() { return maxInFlight.get(); }
    public int totalCalls() { return totalCalls.get(); }
    public int callsFor(String text) { AtomicInteger a = callsPerText.get(text); return a == null ? 0 : a.get(); }
    public List<String> callOrder() { return List.copyOf(callOrder); }

    public static class SimulatedCrash extends Error {
        public SimulatedCrash() { super("simulated process crash"); }
    }
}

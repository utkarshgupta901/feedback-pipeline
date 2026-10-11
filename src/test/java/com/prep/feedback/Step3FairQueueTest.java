package com.prep.feedback;

import com.prep.feedback.fairness.FairQueue;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** STEP 3 - fairness across tenants (round-robin queue).  Run: mvn test -Dtest='Step3*' */
class Step3FairQueueTest {

    private static List<String> takeN(FairQueue<String> q, int n) throws InterruptedException {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(q.take());
        return out;
    }

    @Test
    void singleTenant_isFifo() throws Exception {
        FairQueue<String> q = new FairQueue<>();
        q.offer("A", "a1"); q.offer("A", "a2"); q.offer("A", "a3");
        assertEquals(List.of("a1", "a2", "a3"), takeN(q, 3));
        assertEquals(0, q.size());
    }

    @Test
    void roundRobin_acrossTenants() throws Exception {
        FairQueue<String> q = new FairQueue<>();
        for (int i = 1; i <= 5; i++) q.offer("A", "a" + i);
        q.offer("B", "b1"); q.offer("B", "b2");
        q.offer("C", "c1");
        assertEquals(8, q.size());
        assertEquals(List.of("a1", "b1", "c1", "a2", "b2", "a3", "a4", "a5"), takeN(q, 8));
    }

    @Test
    void hugeTenant_cannotStarveSmallOne() throws Exception {
        FairQueue<String> q = new FairQueue<>();
        for (int i = 0; i < 10_000; i++) q.offer("BIG", "big" + i);
        q.offer("small", "s1"); q.offer("small", "s2"); q.offer("small", "s3");
        List<String> first6 = takeN(q, 6);
        assertTrue(first6.containsAll(List.of("s1", "s2", "s3")), "small tenant starved: " + first6);
    }

    @Test
    void tenantThatDrainsAndComesBack_joinsAtEndOfRotation() throws Exception {
        FairQueue<String> q = new FairQueue<>();
        q.offer("A", "a1"); q.offer("A", "a2");
        q.offer("B", "b1");
        assertEquals(List.of("a1", "b1"), takeN(q, 2)); // B drained
        q.offer("B", "b2");                              // B comes back -> behind A
        q.offer("C", "c1");
        assertEquals(List.of("a2", "b2", "c1"), takeN(q, 3));
    }

    @Test
    void take_blocksUntilSomethingIsOffered() throws Exception {
        FairQueue<String> q = new FairQueue<>();
        AtomicReference<String> got = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Thread t = Thread.ofVirtual().start(() -> {
            try { got.set(q.take()); } catch (InterruptedException ignored) {}
            done.countDown();
        });
        assertFalse(done.await(100, TimeUnit.MILLISECONDS), "take() must block on an empty queue");
        q.offer("A", "hello");
        assertTrue(done.await(1, TimeUnit.SECONDS));
        assertEquals("hello", got.get());
        t.join();
    }

    @Test
    void close_drainsRemainingThenReturnsNull_andRejectsOffers() throws Exception {
        FairQueue<String> q = new FairQueue<>();
        q.offer("A", "a1");
        q.close();
        assertEquals("a1", q.take(), "items offered before close are still delivered");
        assertNull(q.take());
        assertNull(q.take());
        assertThrows(IllegalStateException.class, () -> q.offer("A", "late"));
    }

    @Test
    void close_wakesUpBlockedTakers() {
        FairQueue<String> q = new FairQueue<>();
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<String>> takers = new ArrayList<>();
                for (int i = 0; i < 3; i++) takers.add(pool.submit(q::take));
                Thread.sleep(50);
                q.close();
                for (Future<String> f : takers) assertNull(f.get());
            }
        });
    }

    @Test
    void concurrentProducersAndConsumers_deliverEveryItemExactlyOnce() throws Exception {
        FairQueue<String> q = new FairQueue<>();
        Set<String> seen = ConcurrentHashMap.newKeySet();
        int producers = 4, perProducer = 2_000;
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> consumers = new ArrayList<>();
            for (int c = 0; c < 4; c++) {
                consumers.add(pool.submit(() -> {
                    String s;
                    while ((s = q.take()) != null) assertTrue(seen.add(s), "duplicate delivery: " + s);
                    return null;
                }));
            }
            List<Future<?>> prods = new ArrayList<>();
            for (int p = 0; p < producers; p++) {
                int pid = p;
                prods.add(pool.submit(() -> {
                    for (int i = 0; i < perProducer; i++) q.offer("tenant" + (i % 7), "p" + pid + "-" + i);
                }));
            }
            for (Future<?> f : prods) f.get();
            q.close();
            for (Future<?> f : consumers) f.get();
        }
        assertEquals(producers * perProducer, seen.size());
    }
}

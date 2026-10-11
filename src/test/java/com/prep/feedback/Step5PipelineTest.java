package com.prep.feedback;

import com.prep.feedback.ingest.TextNormalizer;
import com.prep.feedback.model.FeedbackRecord;
import com.prep.feedback.model.Outcome;
import com.prep.feedback.pipeline.Pipeline;
import com.prep.feedback.pipeline.PipelineConfig;
import com.prep.feedback.pipeline.PipelineCrashedException;
import com.prep.feedback.pipeline.Report;
import com.prep.feedback.retry.RetryPolicy;
import com.prep.feedback.retry.Sleeper;
import com.prep.feedback.store.Journal;
import com.prep.feedback.support.FakeClassifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;

import static com.prep.feedback.support.Records.rec;
import static org.junit.jupiter.api.Assertions.*;

/** STEP 5 - wire it all together.  Run: mvn test -Dtest='Step5*' */
class Step5PipelineTest {

    private static final RetryPolicy K4 = new RetryPolicy(4, Duration.ofMillis(1), Duration.ofMillis(5));

    private static Pipeline pipeline(FakeClassifier c, int n, int workers, Journal j) {
        return new Pipeline(c, new PipelineConfig(n, workers, K4), j, Sleeper.REAL, new Random(1));
    }

    private static List<FeedbackRecord> tenantRecords(String tenant, int count, long startTs) {
        List<FeedbackRecord> out = new ArrayList<>();
        for (int i = 0; i < count; i++) out.add(rec(tenant, tenant + i, tenant + " feedback " + i, startTs + i));
        return out;
    }

    @Test
    void endToEnd_cleansDedupesClassifiesAndJournals(@TempDir Path dir) throws Exception {
        FakeClassifier c = FakeClassifier.fast();
        List<FeedbackRecord> input = List.of(
                rec("acme", "1", "Love it", 1),
                rec("acme", "2", "love  IT", 2),          // duplicate
                rec("acme", "3", "  ", 3),                // blank
                rec("acme", "4", "flaky but fine", 4),    // fails twice, then ok
                rec("acme", "5", "always-fail", 5),       // dead letter after K
                rec("globex", "6", "love it", 6));        // other tenant -> not a dup
        try (Journal j = new Journal(dir.resolve("j.log"))) {
            Report r = pipeline(c, 2, 4, j).run(input);
            assertEquals(new Report(2, 0, 3, 1), r);

            Outcome flaky = j.outcomes().get(TextNormalizer.key("acme", "flaky but fine"));
            assertEquals(3, flaky.attempts());
            Outcome dead = j.outcomes().get(TextNormalizer.key("acme", "always-fail"));
            assertInstanceOf(Outcome.DeadLetter.class, dead);
            assertEquals(4, dead.attempts());
        }
        assertEquals(1, c.callsFor("Love it"), "duplicate must not be classified");
    }

    @Test
    void neverMoreThanNCallsInFlight(@TempDir Path dir) throws Exception {
        FakeClassifier c = FakeClassifier.slow(5);
        try (Journal j = new Journal(dir.resolve("j.log"))) {
            Report r = pipeline(c, 3, 8, j).run(tenantRecords("acme", 80, 0));
            assertEquals(80, r.succeeded());
        }
        assertTrue(c.maxInFlight() <= 3, "max in flight was " + c.maxInFlight());
        assertEquals(3, c.maxInFlight(), "with 8 workers we should saturate N=3");
    }

    @Test
    void bigTenantDoesNotStarveSmallOnes_deterministic(@TempDir Path dir) throws Exception {
        List<FeedbackRecord> input = new ArrayList<>(tenantRecords("BIG", 200, 0));     // all older!
        input.addAll(tenantRecords("s1", 5, 1_000));
        input.addAll(tenantRecords("s2", 5, 2_000));
        FakeClassifier c = FakeClassifier.fast();
        try (Journal j = new Journal(dir.resolve("j.log"))) {
            pipeline(c, 1, 1, j).run(input);
        }
        List<String> order = c.callOrder();
        List<String> first15 = order.subList(0, 15);
        long small = first15.stream().filter(t -> t.startsWith("s1") || t.startsWith("s2")).count();
        assertEquals(10, small, "round-robin should interleave BIG,s1,s2,...  got " + first15);
    }

    @Test
    void bigTenantDoesNotStarveSmallOnes_concurrent(@TempDir Path dir) throws Exception {
        List<FeedbackRecord> input = new ArrayList<>(tenantRecords("BIG", 300, 0));
        input.addAll(tenantRecords("s1", 5, 1_000));
        input.addAll(tenantRecords("s2", 5, 2_000));
        FakeClassifier c = FakeClassifier.slow(2);
        try (Journal j = new Journal(dir.resolve("j.log"))) {
            pipeline(c, 2, 4, j).run(input);
        }
        List<String> first30 = c.callOrder().subList(0, 30);
        long small = first30.stream().filter(t -> t.startsWith("s1") || t.startsWith("s2")).count();
        assertEquals(10, small, "small tenants should finish early, got " + first30);
    }

    @Test
    void rerun_skipsEverythingAlreadyProcessed(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("j.log");
        List<FeedbackRecord> batchA = tenantRecords("acme", 20, 0);
        List<FeedbackRecord> batchAB = new ArrayList<>(batchA);
        batchAB.add(rec("acme", "dead", "always-fail", 99));
        batchAB.addAll(tenantRecords("globex", 10, 100));

        try (Journal j = new Journal(f)) {
            assertEquals(20, pipeline(FakeClassifier.fast(), 2, 2, j).run(batchA).succeeded());
        }
        FakeClassifier second = FakeClassifier.fast();
        try (Journal j = new Journal(f)) {
            Report r = pipeline(second, 2, 2, j).run(batchAB);
            assertEquals(new Report(0, 20, 10, 1), r);
        }
        assertEquals(10 + 4, second.totalCalls(), "only new records (10 ok + 4 attempts of the dead one)");

        FakeClassifier third = FakeClassifier.fast();
        try (Journal j = new Journal(f)) {
            assertEquals(new Report(0, 31, 0, 0), pipeline(third, 2, 2, j).run(batchAB));
        }
        assertEquals(0, third.totalCalls(), "dead letters are terminal too - not retried on rerun");
    }

    @Test
    void crashMidRun_thenRerun_processesEachRecordExactlyOnce(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("j.log");
        List<FeedbackRecord> input = tenantRecords("acme", 30, 0);

        try (Journal j = new Journal(f)) {
            Pipeline p = pipeline(FakeClassifier.crashingOnCall(11), 1, 1, j);
            assertThrows(PipelineCrashedException.class, () -> p.run(input));
            assertEquals(10, j.outcomes().size(), "the 10 completed records must be durable");
        }

        FakeClassifier healthy = FakeClassifier.fast();
        try (Journal j = new Journal(f)) {
            Report r = pipeline(healthy, 1, 1, j).run(input);
            assertEquals(10, r.skippedAlreadyDone());
            assertEquals(20, r.succeeded());
            assertEquals(30, j.outcomes().size());
        }
        assertEquals(20, healthy.totalCalls());

        List<String> lines = Files.readAllLines(f);
        assertEquals(30, lines.size());
        assertEquals(30, new HashSet<>(lines.stream().map(l -> l.split("\t")[1]).toList()).size(),
                "some key was journaled twice");
    }
}

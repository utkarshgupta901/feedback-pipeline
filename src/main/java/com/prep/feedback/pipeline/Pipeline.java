package com.prep.feedback.pipeline;

import com.prep.feedback.fairness.FairQueue;
import com.prep.feedback.ingest.Ingest;
import com.prep.feedback.model.FeedbackRecord;
import com.prep.feedback.model.Outcome;
import com.prep.feedback.model.PreparedRecord;
import com.prep.feedback.retry.BoundedClassifier;
import com.prep.feedback.retry.Classifier;
import com.prep.feedback.retry.RetryingClassifier;
import com.prep.feedback.retry.Sleeper;
import com.prep.feedback.store.Journal;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.random.RandomGenerator;

/**
 * Wires everything together:
 *
 *   input -> Ingest.prepare (clean + dedupe) -> skip keys already in Journal
 *         -> FairQueue (round-robin by tenant)
 *         -> W workers: take() -> RetryingClassifier(BoundedClassifier(raw, N)) -> Journal.record()
 */
public class Pipeline {
    private final Classifier rawClassifier;
    private final PipelineConfig config;
    private final Journal journal;
    private final Sleeper sleeper;
    private final RandomGenerator rnd;

    public Pipeline(Classifier rawClassifier, PipelineConfig config, Journal journal, Sleeper sleeper, RandomGenerator rnd) {
        this.rawClassifier = rawClassifier;
        this.config = config;
        this.journal = journal;
        this.sleeper = sleeper;
        this.rnd = rnd;
    }

    /**
     * Processes one batch to completion.
     * If any worker dies with an unexpected Throwable (simulated crash), stop all
     * workers and throw PipelineCrashedException. Everything recorded in the journal
     * before the crash must be skipped by the next run.
     */
    public Report run(List<FeedbackRecord> input) throws InterruptedException {
        // 1. prepared = Ingest.prepare(input); dropped = input.size() - prepared.size()
        // 2. FairQueue: offer every record NOT already journal.isDone(key); count the skipped ones; close() the queue
        // 3. RetryingClassifier wrapping new BoundedClassifier(rawClassifier, N)
        // 4. Start config.workers() virtual threads: loop take() until null -> retrying.run -> journal.record
        // 5. Wait for all workers. If one failed: shutdownNow() + throw new PipelineCrashedException(cause)
        // 6. return new Report(dropped, skipped, succeeded, deadLettered)
        throw new UnsupportedOperationException("TODO step 5");
    }
}

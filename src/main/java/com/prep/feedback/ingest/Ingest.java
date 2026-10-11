package com.prep.feedback.ingest;

import com.prep.feedback.model.FeedbackRecord;
import com.prep.feedback.model.PreparedRecord;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Ingest {
    private Ingest() {}

    /**
     * Clean + de-duplicate per tenant.
     * <ul>
     *   <li>Records with null tenant or text that is blank after cleaning are dropped.</li>
     *   <li>Duplicates = same tenant AND same key (see {@link TextNormalizer#key}).
     *       The SAME text under two different tenants is NOT a duplicate.</li>
     *   <li>When duplicates exist, keep the one with the earliest timestamp
     *       (ties: the one that appeared first in the input). This makes the choice
     *       deterministic regardless of arrival order - important for safe reruns.</li>
     *   <li>Output is sorted by timestamp ascending (stable).</li>
     * </ul>
     */
    public static List<PreparedRecord> prepare(List<FeedbackRecord> input) {
        // Hint: LinkedHashMap<String, PreparedRecord> keyed by TextNormalizer.key(...) + Map.merge(...).

        throw new UnsupportedOperationException("TODO step 1c");
    }
}

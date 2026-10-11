package com.prep.feedback;

import com.prep.feedback.ingest.Ingest;
import com.prep.feedback.ingest.TextNormalizer;
import com.prep.feedback.model.FeedbackRecord;
import com.prep.feedback.model.PreparedRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static com.prep.feedback.support.Records.rec;
import static org.junit.jupiter.api.Assertions.*;

/** STEP 1 - clean text + drop duplicates per tenant.  Run: mvn test -Dtest='Step1*' */
class Step1IngestTest {

    // ---------- TextNormalizer.clean ----------

    @Test
    void clean_collapsesWhitespaceAndTrims() {
        assertEquals("app keeps crashing", TextNormalizer.clean("  app \t keeps\n\ncrashing  \r\n"));
    }

    @Test
    void clean_removesInvisibleCharsAndNormalizesUnicode() {
        assertEquals("Hello world", TextNormalizer.clean("Ｈｅｌｌｏ world"));     // full-width + NBSP (NFKC)
        assertEquals("no zero width", TextNormalizer.clean("no​ zero﻿ width")); // zero-width space, BOM
        assertEquals("a b", TextNormalizer.clean("a\u0000b"));                           // control char -> space
    }

    @Test
    void clean_returnsNullForNullBlankOrInvisibleOnly() {
        assertNull(TextNormalizer.clean(null));
        assertNull(TextNormalizer.clean(""));
        assertNull(TextNormalizer.clean("   \n\t "));
        assertNull(TextNormalizer.clean("​﻿"));
    }

    @Test
    void clean_keepsCase() {
        assertEquals("Great APP", TextNormalizer.clean("Great APP"));
    }

    // ---------- TextNormalizer.key ----------

    @Test
    void key_isCaseInsensitiveDeterministicAndTenantScoped() {
        String k1 = TextNormalizer.key("acme", "Great App");
        String k2 = TextNormalizer.key("acme", "great app");
        String k3 = TextNormalizer.key("globex", "great app");
        assertEquals(k1, k2);
        assertNotEquals(k1, k3);
        assertTrue(k1.startsWith("acme:"), "key should be prefixed with tenant");
        assertEquals("acme:".length() + 64, k1.length(), "tenant + ':' + 64 hex chars of sha-256");
    }

    // ---------- Ingest.prepare ----------

    @Test
    void prepare_dropsBlankTextAndNullTenant() {
        List<PreparedRecord> out = Ingest.prepare(List.of(
                rec("acme", "1", "   ", 1),
                rec("acme", "2", null, 2),
                rec(null, "3", "orphan", 3),
                rec("acme", "4", "real feedback", 4)));
        assertEquals(1, out.size());
        assertEquals("real feedback", out.get(0).cleanText());
    }

    @Test
    void prepare_dedupesWithinTenant_butNotAcrossTenants() {
        List<PreparedRecord> out = Ingest.prepare(List.of(
                rec("acme", "1", "Love it", 1),
                rec("acme", "2", "  love   IT ", 2),   // dup of #1 for acme
                rec("globex", "3", "love it", 3),     // same text, different tenant -> keep
                rec("acme", "4", "hate it", 4)));
        assertEquals(3, out.size());
        assertEquals(2, out.stream().filter(p -> p.tenant().equals("acme")).count());
        assertEquals(1, out.stream().filter(p -> p.tenant().equals("globex")).count());
    }

    @Test
    void prepare_keepsEarliestDuplicate_regardlessOfInputOrder() {
        FeedbackRecord late = rec("acme", "late", "same text", 50);
        FeedbackRecord early = rec("acme", "early", "SAME TEXT", 10);
        assertEquals("early", Ingest.prepare(List.of(late, early)).get(0).original().id());
        assertEquals("early", Ingest.prepare(List.of(early, late)).get(0).original().id());
    }

    @Test
    void prepare_tieOnTimestamp_keepsFirstSeen() {
        FeedbackRecord a = rec("acme", "a", "dup", 10);
        FeedbackRecord b = rec("acme", "b", "dup", 10);
        assertEquals("a", Ingest.prepare(List.of(a, b)).get(0).original().id());
    }

    @Test
    void prepare_sortsByTimestamp_andSetsKey() {
        List<PreparedRecord> out = Ingest.prepare(List.of(
                rec("acme", "1", "third", 30),
                rec("acme", "2", "first", 10),
                rec("globex", "3", "second", 20)));
        assertEquals(List.of("first", "second", "third"), out.stream().map(PreparedRecord::cleanText).toList());
        assertEquals(TextNormalizer.key("acme", "first"), out.get(0).key());
        assertEquals(Instant.ofEpochSecond(10), out.get(0).original().timestamp());
    }
}

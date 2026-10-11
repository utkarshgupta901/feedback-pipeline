package com.prep.feedback;

import com.prep.feedback.model.Outcome;
import com.prep.feedback.store.Journal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** STEP 4 - crash-safe "already processed" store.  Run: mvn test -Dtest='Step4*' */
class Step4JournalTest {

    @Test
    void freshJournal_isEmpty(@TempDir Path dir) {
        try (Journal j = new Journal(dir.resolve("sub/journal.log"))) {   // parent dirs created
            assertTrue(j.outcomes().isEmpty());
            assertFalse(j.isDone("acme:x"));
        }
    }

    @Test
    void outcomesSurviveReopen(@TempDir Path dir) {
        Path f = dir.resolve("journal.log");
        Outcome ok = new Outcome.Success("acme:1", "bug", 2);
        Outcome dead = new Outcome.DeadLetter("acme:2", 5, "java.lang.RuntimeException: 503");
        try (Journal j = new Journal(f)) {
            j.record(ok);
            j.record(dead);
            assertTrue(j.isDone("acme:1"));
        }
        try (Journal j = new Journal(f)) {                  // "restart"
            assertTrue(j.isDone("acme:1"));
            assertTrue(j.isDone("acme:2"));
            assertEquals(ok, j.outcomes().get("acme:1"));
            assertEquals(dead, j.outcomes().get("acme:2"));
        }
    }

    @Test
    void awkwardCharactersRoundTrip(@TempDir Path dir) {
        Path f = dir.resolve("journal.log");
        Outcome nasty = new Outcome.DeadLetter("acme:3", 1, "line1\nline2\ttabbed \\ back\\slash \\n literal\r");
        Outcome label = new Outcome.Success("acme:4", "feature\trequest\n", 1);
        try (Journal j = new Journal(f)) { j.record(nasty); j.record(label); }
        try (Journal j = new Journal(f)) {
            assertEquals(nasty, j.outcomes().get("acme:3"));
            assertEquals(label, j.outcomes().get("acme:4"));
        }
    }

    @Test
    void duplicateRecord_isIgnored(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("journal.log");
        try (Journal j = new Journal(f)) {
            j.record(new Outcome.Success("acme:1", "bug", 1));
            j.record(new Outcome.Success("acme:1", "praise", 1));
            assertEquals("bug", ((Outcome.Success) j.outcomes().get("acme:1")).label(), "first write wins");
        }
        assertEquals(1, Files.readAllLines(f).size());
    }

    @Test
    void tornLastLine_fromCrashMidWrite_isIgnoredAndRepaired(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("journal.log");
        try (Journal j = new Journal(f)) { j.record(new Outcome.Success("acme:1", "bug", 1)); }
        // simulate crash in the middle of writing the 2nd line (no trailing newline)
        Files.writeString(f, "S\tacme:2\tpra", StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        try (Journal j = new Journal(f)) {
            assertTrue(j.isDone("acme:1"));
            assertFalse(j.isDone("acme:2"), "half-written entry must not count as processed");
            j.record(new Outcome.Success("acme:3", "praise", 1));   // must start on a clean line
        }
        try (Journal j = new Journal(f)) {
            assertTrue(j.isDone("acme:1"));
            assertFalse(j.isDone("acme:2"));
            assertTrue(j.isDone("acme:3"), "append after recovery got glued to the torn line?");
        }
        List<String> lines = Files.readAllLines(f);
        assertEquals(2, lines.size(), "torn bytes should have been truncated: " + lines);
    }

    @Test
    void garbageLines_areSkipped(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("journal.log");
        Files.writeString(f, "S\tacme:1\tbug\t1\nthis is junk\nD\tacme:2\tNaN\toops\n\nD\tacme:3\t2\tboom\n");
        try (Journal j = new Journal(f)) {
            assertTrue(j.isDone("acme:1"));
            assertFalse(j.isDone("acme:2"));
            assertTrue(j.isDone("acme:3"));
        }
    }
}

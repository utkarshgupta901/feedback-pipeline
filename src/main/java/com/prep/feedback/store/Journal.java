package com.prep.feedback.store;

import com.prep.feedback.model.Outcome;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Append-only, fsync'ed log of terminal outcomes. This is our "processed" set.
 *
 * Line format (fields separated by TAB, line ends with \n):
 *   S  key  label  attempts
 *   D  key  attempts  lastError
 * Fields are escaped: '\' -> "\\", TAB -> "\t", LF -> "\n", CR -> "\r".
 *
 * Crash safety:
 *  - each append is written then forced to disk before returning;
 *  - on open, a torn last line (no trailing \n, i.e. we crashed mid-write) is ignored
 *    AND truncated away, so the next append starts on a clean line;
 *  - if a key appears twice (should never happen) the first entry wins.
 */
public class Journal implements AutoCloseable {
    private final FileChannel channel;
    private final Map<String, Outcome> outcomes = new LinkedHashMap<>();

    public Journal(Path file) {
        try {
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            this.channel = FileChannel.open(file, StandardOpenOption.CREATE,
                    StandardOpenOption.READ, StandardOpenOption.WRITE);
            recover();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void recover() throws IOException {
        // 1. read whole file  2. parse only up to the last '\n'  3. truncate torn tail  4. position channel at end
        throw new UnsupportedOperationException("TODO step 4a");
    }

    public synchronized boolean isDone(String key) {
        return outcomes.containsKey(key);
    }

    /** Durably record a terminal outcome. Must be on disk when this returns. Duplicate keys are ignored. */
    public synchronized void record(Outcome outcome) {
        // Build the line with esc(...), write ALL bytes, then channel.force(false). Update the map only AFTER the write.
        throw new UnsupportedOperationException("TODO step 4b");
    }

    public synchronized Map<String, Outcome> outcomes() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(outcomes));
    }

    @Override
    public synchronized void close() {
        try {
            channel.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---- encoding helpers ----

    static Outcome parse(String line) {
        // Use line.split("\t", -1). Return null for anything malformed (wrong field count, bad number).
        throw new UnsupportedOperationException("TODO step 4a");
    }

    static String esc(String s) {
        if (s == null) s = "";
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '\t' -> sb.append("\\t");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    static String unesc(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                sb.append(switch (n) {
                    case 't' -> '\t';
                    case 'n' -> '\n';
                    case 'r' -> '\r';
                    default -> n;
                });
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}

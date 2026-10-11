package com.prep.feedback.ingest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

public final class TextNormalizer {
    private static final Pattern FORMAT_CHARS = Pattern.compile("\\p{Cf}");   // zero-width space, BOM, ...
    private static final Pattern CONTROL_CHARS = Pattern.compile("\\p{Cc}");  // \n, \t, \r, \u0000 ...
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private TextNormalizer() {}

    /**
     * Cleans raw feedback text.
     * Rules: Unicode NFKC, remove invisible format chars, turn control chars into spaces,
     * collapse runs of whitespace into one space, trim.
     *
     * @return cleaned text, or null if input is null or nothing is left after cleaning
     */
    public static String clean(String raw) {
        // Hint: java.text.Normalizer (NFKC), then the 3 precompiled patterns above, in order.
        if (raw == null) return null;
        String s = Normalizer.normalize(raw, Normalizer.Form.NFKC);  // 1. NFKC
        s = FORMAT_CHARS.matcher(s).replaceAll("");                  // 2. remove invisible format chars
        s = CONTROL_CHARS.matcher(s).replaceAll(" ");                // 3. control chars -> space
        s = WHITESPACE.matcher(s).replaceAll(" ");                   // 4. collapse whitespace
        return s.strip();
    }

    /**
     * Idempotency / dedupe key: tenant + ":" + sha256(lowercased clean text) in hex.
     * Case-insensitive so "Great app" and "great APP" are duplicates.
     */
    public static String key(String tenant, String cleanText) {
        // Hint: sha256Hex(...) is provided below. Lowercase with Locale.ROOT (why ROOT? see LEARNING.md).
        throw new UnsupportedOperationException("TODO step 1b");
    }

    static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

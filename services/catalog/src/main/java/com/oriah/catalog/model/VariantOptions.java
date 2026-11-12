package com.oriah.catalog.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * The option combination that distinguishes one variant of a product from
 * another: {@code {"Ring Size": "M", "Metal": "18k Yellow Gold"}}.
 *
 * <h2>Why a digest exists at all</h2>
 *
 * {@code product_variants} carries {@code UNIQUE (product_id,
 * option_digest)}, so "two variants with the same options" is rejected by
 * Postgres rather than by a read-then-write check in the service. That matters
 * because the check-then-insert version loses to two admins saving at once:
 * both read "no such combination", both insert, and the product ends up with
 * a duplicate variant that the storefront renders as two identical selector
 * entries.
 *
 * <h2>Why canonical JSON rather than a joined string</h2>
 *
 * The first design joined sorted {@code key=value} pairs with {@code '|'}.
 * That is broken in two ways that are both reachable from an admin form:
 *
 * <ul>
 *   <li>{@code {a: "b", c: "d"}} and {@code {a: "b|c=d"}} both flatten to
 *       {@code a=b|c=d}. Two different variants, one digest — so the unique
 *       constraint would reject a legitimate second variant.</li>
 *   <li>"Rosé" composed (U+00E9) and decomposed (e + U+0301) are different
 *       strings that render identically, so they would be two variants an
 *       operator cannot tell apart.</li>
 * </ul>
 *
 * JSON fixes the first because the separator is structural and the data is
 * escaped; NFC normalisation fixes the second. The JSON is written by hand
 * rather than through Jackson deliberately: canonicalisation needs output
 * that is stable forever, and a library is free to change its key ordering,
 * escaping choices or whitespace between versions. There are no such degrees
 * of freedom here.
 *
 * <h2>The contract with the migration</h2>
 *
 * {@link #canonicalJson} of an empty map is exactly {@code "{}"}, whose
 * SHA-256 is {@code 44136fa3...aff8a}. That is the literal
 * {@code V3__product_types_variants_categories_images.sql} wrote for every
 * backfilled variant, via {@code encode(sha256('{}'::bytea), 'hex')}. If this
 * class ever stops agreeing, legacy variants' stored digests stop matching
 * what the application computes and the uniqueness guarantee quietly lapses.
 * {@code VariantOptionsTest} pins the constant on this side; the migration
 * computes it rather than pasting it on the other.
 */
public final class VariantOptions {

    /** Long enough for "18k Yellow Gold Vermeil"; short enough to be a label. */
    private static final int MAX_LENGTH = 64;

    private VariantOptions() {
    }

    /**
     * Validates, trims and NFC-normalises an option map, returning the form
     * that gets both stored in {@code options} and fed to {@link #digest}.
     *
     * <p>These must be the same value. If the stored JSONB differed from what
     * was digested, reading a variant back and recomputing its digest would
     * produce a different answer, and the unique constraint would be guarding
     * a key nothing else can reproduce.
     *
     * @param options may be null, which means "this product does not vary on
     *                any axis" — a one-off piece, and what the V3 backfill
     *                produced for every pre-existing product
     * @throws IllegalArgumentException on a blank, over-long, or
     *                                  control-character-bearing key or value,
     *                                  or on two keys that normalise to the same name
     */
    public static Map<String, String> validated(Map<String, String> options) {
        var out = new LinkedHashMap<String, String>();
        if (options == null) {
            return out;
        }
        for (var entry : options.entrySet()) {
            String key = normalise(entry.getKey(), "option name");
            String value = normalise(entry.getValue(), "option value for '" + key + "'");
            // Two raw keys can normalise to one ("Size" and " Size ", or the two
            // Unicode spellings of the same accented letter). Merged, the later
            // value silently overwrote the earlier one and the variant was stored
            // with an option combination nobody submitted.
            if (out.put(key, value) != null) {
                throw new IllegalArgumentException(
                        "option name '" + key + "' is given more than once");
            }
        }
        return out;
    }

    private static String normalise(String raw, String what) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(what + " must not be blank");
        }
        // Checked on the RAW input, before trimming, and this ordering is
        // load-bearing. String.trim() strips everything <= U+0020, so a value
        // of "M\u0000" would otherwise arrive here, lose its NUL, and be
        // accepted as the perfectly clean "M". That leniency is worse than it
        // looks: Postgres cannot store a NUL byte in text or jsonb at all, so
        // any control character that did survive normalisation would surface
        // as an opaque driver-level error on insert instead of a 400 naming
        // the offending field. Rejecting rather than escaping, for the same
        // reason — a newline inside an option name is something no admin form
        // round-trips and no selector label renders.
        for (int i = 0; i < raw.length(); i++) {
            if (Character.isISOControl(raw.charAt(i))) {
                throw new IllegalArgumentException(
                        what + " must not contain control characters");
            }
        }
        String trimmed = Normalizer.normalize(raw.trim(), Normalizer.Form.NFC);
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(what + " must not be blank");
        }
        if (trimmed.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    what + " must be at most " + MAX_LENGTH + " characters");
        }
        return trimmed;
    }

    /**
     * The canonical JSON form: keys NFC-normalised and sorted by Unicode code
     * point, values NFC-normalised and trimmed, no insignificant whitespace.
     *
     * <p>Sorting by code point rather than with a locale-sensitive collator is
     * deliberate — a {@code Collator} would make the digest depend on the JVM
     * default locale, so the same variant would hash differently on a
     * developer's machine and in the container.
     */
    public static String canonicalJson(Map<String, String> options) {
        var sorted = new TreeMap<String, String>(validated(options));

        var json = new StringBuilder("{");
        boolean first = true;
        for (var entry : sorted.entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            appendQuoted(json, entry.getKey());
            json.append(':');
            appendQuoted(json, entry.getValue());
        }
        return json.append('}').toString();
    }

    /** 64 lowercase hex characters, matching the {@code CHAR(64)} column. */
    public static String digest(Map<String, String> options) {
        byte[] hash = sha256(canonicalJson(options).getBytes(StandardCharsets.UTF_8));
        var hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            // Every JVM is required to ship SHA-256, so this is unreachable
            // rather than a condition worth surfacing to a caller.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /**
     * Minimal RFC 8259 string escaping. Control characters never reach here —
     * {@link #normalise} rejects them — so the only cases that matter are the
     * two JSON syntax characters.
     */
    private static void appendQuoted(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') {
                out.append('\\');
            }
            out.append(c);
        }
        out.append('"');
    }
}

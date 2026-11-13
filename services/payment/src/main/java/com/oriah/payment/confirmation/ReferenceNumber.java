package com.oriah.payment.confirmation;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The reference a provider or bank shows for a payment, in the one spelling it is stored and compared in.
 *
 * <p>The same payment must collide with itself however a person types it: spaces anywhere are removed, letters are upper-cased (with the
 * root locale, so the Turkish dotless i is not a special case), and full-width forms read as the ordinary ones (NFKC). Nothing but letters,
 * digits and {@code . _ - /} survives: a zero-width space or an emoji pasted from a message would otherwise make two references that look
 * identical differ, and a duplicate would slip past the key.
 */
public final class ReferenceNumber {

    static final int MIN = 3;
    static final int MAX = 64;
    private static final Pattern ALLOWED = Pattern.compile("[A-Z0-9._/\\-]+");

    private ReferenceNumber() {
    }

    /**
     * @return the reference trimmed, upper-cased and with every space removed
     * @throws IllegalArgumentException if it is empty, not made of letters, digits and . _ - /, or not 3 to 64 characters
     */
    public static String normalise(String reference) {
        if (reference == null) {
            throw new IllegalArgumentException("a reference is required");
        }
        String folded = Normalizer.normalize(reference, Normalizer.Form.NFKC);
        StringBuilder kept = new StringBuilder(folded.length());
        folded.codePoints().filter(cp -> !Character.isWhitespace(cp) && !Character.isSpaceChar(cp)).forEach(kept::appendCodePoint);
        String normalised = kept.toString().toUpperCase(Locale.ROOT);
        if (normalised.isEmpty()) {
            throw new IllegalArgumentException("a reference is required");
        }
        if (normalised.length() < MIN) {
            throw new IllegalArgumentException("a reference is at least " + MIN + " characters, was " + normalised.length());
        }
        if (normalised.length() > MAX) {
            throw new IllegalArgumentException("a reference is at most " + MAX + " characters, was " + normalised.length());
        }
        if (!ALLOWED.matcher(normalised).matches()) {
            throw new IllegalArgumentException("a reference is letters, digits and . _ - / only");
        }
        return normalised;
    }
}

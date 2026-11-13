package com.oriah.order.model;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The reference a provider or bank shows for a payment, in the ONE spelling it is stored and sent in: spaces anywhere removed, letters upper-cased
 * (root locale), full-width forms read as ordinary ones (NFKC), and nothing but letters, digits and {@code . _ - /}.
 *
 * <p>It is the same rule payment applies to the reference staff type when they confirm money (payment's {@code ReferenceNumber}): the two are
 * compared byte for byte, so a claim and the money it names must collide with each other however a person typed them. Order owns a copy because
 * services share no code but the shared modules; the vectors in {@code PaymentReferenceTest} are the same ones payment's own test pins.
 */
public final class PaymentReference {

    public static final int MIN = 3;
    public static final int MAX = 64;
    private static final Pattern ALLOWED = Pattern.compile("[A-Z0-9._/\\-]+");

    private PaymentReference() {
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

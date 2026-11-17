package com.oriah.payment.provider;

import java.security.SecureRandom;
import java.util.regex.Pattern;

/**
 * Oriah's reference for one attempt to collect money through ZynlePay: {@code OZ} and 14 characters.
 *
 * <p>It is what ZynlePay is asked to collect under, what its callback names, and the reference of the confirmation the money is recorded as (it is
 * already in the one spelling {@code ReferenceNumber} keeps). It is random (70 bits), never a counter: it cannot be guessed to aim a forged callback
 * at a payment in flight, and it gives away no count of payments. The alphabet has no I, L, O or U, so it can be read out over the phone.
 *
 * <p>A reference is sent to ZynlePay at most once and never used again.
 */
public final class ProviderReference {

    private static final String PREFIX = "OZ";
    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final int RANDOM_CHARACTERS = 14;
    private static final Pattern OURS = Pattern.compile("^OZ[0-9A-HJKMNP-TV-Z]{14}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private ProviderReference() {
    }

    public static String mint() {
        StringBuilder reference = new StringBuilder(PREFIX.length() + RANDOM_CHARACTERS).append(PREFIX);
        for (int i = 0; i < RANDOM_CHARACTERS; i++) {
            reference.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return reference.toString();
    }

    /** Whether this is the shape of a reference Oriah mints: told apart before the database is asked. */
    public static boolean isOurs(String reference) {
        return reference != null && OURS.matcher(reference).matches();
    }
}

package com.oriah.quote;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * A digest of the whole payable quote, so that only the quote the shopper saw
 * can be charged (MVP_PLAN Phase 0, item 4.4).
 *
 * <p>Preview returns it; confirm sends it back; order prices everything
 * itself, computes its own digest and -- before saving anything -- refuses
 * with 409 when the two differ. One implementation, used by both services.
 *
 * <h2>Canonical form</h2>
 *
 * UTF-8, with the unit separator (U+001F) between fields and the record
 * separator (U+001E) after each record -- characters that cannot occur in
 * any value:
 * <pre>
 *   "oriah-quote/2" RS
 *   currency RS
 *   deliveryOption US deliveryTownId US payer US n(deliveryAmount) RS
 *   n(total) RS
 *   each line, sorted by its own text: variantId US quantity US n(unitPrice) US n(discountPerPiece) US promotionId RS
 * </pre>
 * where {@code n(x)} is the amount with trailing zeros removed, so 100, 100.0
 * and 100.00 are one price, and an absent town, payer or promotion is the
 * empty string. Version 2 (Phase 7) binds what the shopper agreed to about
 * delivery -- the town it is priced for and who pays the courier -- and which
 * promotion took the discount off each line. The version prefix is part of the
 * hashed text, so a digest made in version 1's form can never be accepted for
 * a version 2 quote, nor the other way round. Sorting by the whole line gives
 * a total order even when one variant appears twice.
 */
public final class QuoteDigest {

    /** 64 lowercase hex characters: a SHA-256. */
    public static final String PATTERN = "^[0-9a-f]{64}$";

    private static final char US = '\u001f';
    private static final char RS = '\u001e';

    private QuoteDigest() {
    }

    public static String of(Quote quote) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest(canonical(quote).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    static String canonical(Quote quote) {
        StringBuilder out = new StringBuilder()
                .append("oriah-quote/2").append(RS)
                .append(quote.currency()).append(RS)
                .append(quote.deliveryOption()).append(US)
                .append(quote.deliveryTownId() == null ? "" : quote.deliveryTownId().toString()).append(US)
                .append(quote.payer() == null ? "" : quote.payer()).append(US)
                .append(n(quote.deliveryAmount())).append(RS)
                .append(n(quote.total())).append(RS);
        quote.lines().stream()
                .map(l -> l.variantId() + String.valueOf(US) + l.quantity() + US
                        + n(l.unitPrice()) + US + n(l.discountPerPiece()) + US
                        + (l.promotionId() == null ? "" : l.promotionId().toString()) + RS)
                .sorted()
                .forEach(out::append);
        return out.toString();
    }

    /**
     * Trailing zeros removed. Safe to print in full: every amount here has
     * passed the ngwee rule (at most 10 whole digits), so the plain form is
     * short -- never a two-billion-digit string from a crafted exponent.
     */
    private static String n(BigDecimal amount) {
        return amount.stripTrailingZeros().toPlainString();
    }
}
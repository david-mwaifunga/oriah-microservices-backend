package com.oriah.checkout.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;

import com.oriah.checkout.client.CatalogPricingClient;

/**
 * A digest of the prices a shopper was shown, so confirm can tell whether they
 * are agreeing to them.
 *
 * <h2>Recomputed, never stored</h2>
 *
 * Checkout is stateless: no database, no Flyway, no JPA, and a STATELESS
 * session policy. It therefore cannot remember what preview quoted, which
 * sounds like a problem and is actually the design. The version is derived
 * from catalog's answer, so preview and confirm compute the same value from
 * the same facts without anything being persisted between them -- and the
 * client's copy can only ever be COMPARED, never trusted as input to a price.
 *
 * <h2>What goes in, and the two surprises</h2>
 *
 * Per line: the variant id, the price, the currency and the status. Sorted by
 * variant id.
 *
 * <p>Sorting is the reason this works at all. Cart's {@code items} has no
 * {@code @OrderBy} and its repository fetches by entity graph with no ORDER BY,
 * so two reads of an unchanged bag can come back in different orders. A digest
 * over the response sequence would mismatch for reasons unrelated to price,
 * and every shopper would meet a conflict on a bag nobody had touched.
 *
 * <p>Quantity is excluded: a shopper changing their own quantity has not been
 * shown a different price, and the total is recomputed at confirm anyway.
 * Including it would turn nudging a stepper into a 409.
 *
 * <p>Status is included, which is less obvious. A variant going UNPUBLISHED
 * between preview and confirm leaves the remaining prices untouched, so a
 * price-only digest would still match and the shopper would be refused by the
 * availability check with no explanation of what had changed.
 *
 * <h2>Prices are normalised before hashing</h2>
 *
 * {@code BigDecimal.toString()} keeps scale, so 4500.00 and 4500.000 -- the
 * same amount of money -- would hash differently. A column widened by a
 * migration, or a change in how catalog formats its response, would then look
 * to every shopper mid-checkout like a repricing. {@code stripTrailingZeros}
 * plus {@code toPlainString} makes the digest depend on the VALUE.
 */
final class PricingVersion {

    private PricingVersion() {
    }

    /** Field and record separators that cannot occur inside the values. */
    private static final char FIELD = '\u001f';
    private static final char RECORD = '\u001e';

    /**
     * The two halves are compared at DIFFERENT points in the saga, which is
     * why the token is composite rather than one digest.
     *
     * <p>The catalog half is checked before anything is ordered or charged.
     * The shipping half cannot be: the quote depends on the discounted
     * subtotal, so it is not known until the promo has been applied. Splitting
     * the token lets each half be compared as soon as it can be.
     */
    private static final char HALVES = '.';

    /**
     * Describes the shipping component of a quote: the zone, and whether a
     * quote was obtained at all.
     *
     * <h2>Why this is in the version</h2>
     *
     * A shipping outage during preview shows the shopper an items-only total
     * with an otherwise valid token. If shipping recovers before they press
     * Place order, confirm quotes successfully and charges MORE than the screen
     * said -- the one direction this guard exists to make impossible. The
     * catalog prices are byte-identical in that scenario, so a digest over
     * catalog alone validates it.
     *
     * <h2>What is deliberately NOT in it</h2>
     *
     * The shipping COST. It is a function of the discounted subtotal, so it
     * moves whenever the shopper changes a quantity -- and a quantity edit must
     * not invalidate the prices they were quoted (see the class javadoc). Zone
     * plus "was it quoted" captures the two transitions that can change the
     * total without the shopper doing anything, and ignores the one they cause
     * themselves.
     */
    static String shippingComponent(String zone, boolean quoted) {
        return quoted ? "q:" + (zone == null ? "?" : zone) : "noquote";
    }

    /** Combines the catalog digest with the shipping component. */
    static String compose(String catalogDigest, String shippingComponent) {
        return catalogDigest + HALVES + shippingComponent;
    }

    /** The catalog half of a token a client echoed, or the whole thing if it carries no half. */
    static String catalogHalfOf(String token) {
        if (token == null) {
            return "";
        }
        int at = token.indexOf(HALVES);
        return at < 0 ? token : token.substring(0, at);
    }

    /** The shipping half of a token a client echoed, or "" if absent. */
    static String shippingHalfOf(String token) {
        if (token == null) {
            return "";
        }
        int at = token.indexOf(HALVES);
        return at < 0 ? "" : token.substring(at + 1);
    }

    static String of(Collection<CatalogPricingClient.Entry> entries) {
        StringBuilder canonical = new StringBuilder();
        entries.stream()
                .sorted(Comparator.comparing(e -> String.valueOf(e.variantId())))
                .forEach(e -> canonical
                        .append(e.variantId()).append(FIELD)
                        .append(normalise(e.unitPrice())).append(FIELD)
                        .append(e.currency()).append(FIELD)
                        .append(e.status()).append(RECORD));
        return sha256(canonical.toString());
    }

    private static String normalise(BigDecimal amount) {
        if (amount == null) {
            return "";
        }
        // stripTrailingZeros turns 0.00 into 0E-2, hence toPlainString.
        return amount.stripTrailingZeros().toPlainString();
    }

    private static String sha256(String canonical) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JDK; if it is missing the JVM is
            // broken in ways that matter more than a checkout.
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}

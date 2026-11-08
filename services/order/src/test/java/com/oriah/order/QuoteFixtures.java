package com.oriah.order;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.oriah.quote.Quote;
import com.oriah.quote.QuoteDigest;
import com.oriah.quote.QuoteLine;

/**
 * The quote digest a test body carries: the digest of what the shopper SAW.
 *
 * <p>Since quote-then-commit (MVP_PLAN Phase 0, item 4.4) order refuses to
 * place anything whose digest differs from its own pricing. So a test that
 * expects an order to be placed must send the digest of exactly what order
 * will price -- computed here with the same shared {@link QuoteDigest}
 * checkout's preview uses, never pasted, so a test cannot drift from the rule.
 */
public final class QuoteFixtures {

    /**
     * Well-formed, matching nothing. Only for bodies that are refused BEFORE
     * the digest is compared (a malformed field, a refused claim), so each of
     * those still carries exactly one fault.
     */
    public static final String ANY_DIGEST = "0".repeat(64);

    private QuoteFixtures() {
    }

    /** One line as the shopper saw it, with no discount (none until Phase 7). */
    public static QuoteLine line(UUID variantId, int quantity, String unitPrice) {
        return new QuoteLine(variantId, quantity, new BigDecimal(unitPrice), BigDecimal.ZERO);
    }

    /** The digest of a quote with this option, delivery amount and lines. */
    public static String digest(String currency, String option, String delivery, QuoteLine... lines) {
        return QuoteDigest.of(new Quote(List.of(lines), currency, option, new BigDecimal(delivery)));
    }

    /** The digest of a self-collect quote: no delivery charge. */
    public static String selfCollect(String currency, QuoteLine... lines) {
        return digest(currency, "SELF_COLLECT", "0.00", lines);
    }
}

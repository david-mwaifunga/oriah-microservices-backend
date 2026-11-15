package com.oriah.order.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.http.HttpStatus;

import com.oriah.money.Ngwee;
import com.oriah.order.exception.SalesProblem;
import com.oriah.quote.Quote;

/**
 * A delivery agreed by phone or WhatsApp (Phase 9, design D5): the amount staff key as the order's delivery charge. A pure rule: no Spring, no database.
 *
 * <p>The amount enters an order ONLY here (a customer checkout can never carry one: the customer's charge is always the published town price). It is the
 * order's fact once written and never changes after the customer has committed (Q24); free delivery is the owner's or manager's waiver, never a K0.00
 * keyed by whoever records the sale. What an agreed delivery may be:
 *
 * <ul>
 *   <li>an option that delivers (a pickup is recorded as a pickup, not as an agreed delivery) paid by Oriah or by the recipient;</li>
 *   <li>paid by the recipient the money never reaches Oriah: K0.00 on the order, whatever the courier will ask;</li>
 *   <li>paid to Oriah: a positive whole-ngwee amount up to a typo ceiling, and Oriah's own delivery is never recipient-paid (Q27: no driver cash float).</li>
 * </ul>
 *
 * When the shop already has a quote for the same town, option, payer and goods, an agreed amount that DIFFERS from it, higher or lower, must carry a
 * reason: a price lower than the website's is a discount nobody chose from a promotion (Q12), and one higher is a surprise the caller accepted.
 */
public final class AgreedDelivery {

    private static final String OWN_DELIVERY = "OWN_DELIVERY";

    static final int REASON_MIN = 5;
    static final int REASON_MAX = 200;

    private AgreedDelivery() {
    }

    /**
     * @param amount  what was agreed (null for a recipient-paid courier)
     * @param ceiling the largest charge accepted (a typo guard, {@code oriah.sales.max-fare})
     * @return what the order charges for delivery, at two decimals
     * @throws SalesProblem 400 {@code invalid-delivery}
     */
    public static BigDecimal check(String optionCode, String payer, BigDecimal amount, BigDecimal ceiling) {
        if (optionCode == null || optionCode.isBlank() || Quote.SELF_COLLECT.equals(optionCode)) {
            throw invalid("an agreed delivery needs a delivering option: a pickup is recorded as a pickup");
        }
        if (Quote.RECIPIENT_PAYS.equals(payer)) {
            if (OWN_DELIVERY.equals(optionCode)) {
                throw invalid("Oriah's own delivery is paid to Oriah with the order, never by the recipient to the driver");
            }
            if (amount != null && amount.signum() != 0) {
                throw invalid("a courier the recipient pays is K0.00 on the order: the recipient pays the courier directly");
            }
            return BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);
        }
        if (!Quote.ORIAH_COLLECTS.equals(payer)) {
            throw invalid("say who pays the courier: Oriah collects it with the order, or the recipient pays it on arrival");
        }
        if (amount == null || amount.signum() <= 0 || !Ngwee.isWholeNgwee(amount) || amount.compareTo(ceiling) > 0) {
            throw invalid("the agreed delivery must be more than K0.00 and at most K" + ceiling.toPlainString() + ", in whole ngwee");
        }
        return amount.setScale(2, RoundingMode.UNNECESSARY);
    }

    /**
     * @param agreed    what was agreed, as the order will charge it
     * @param reference the shop's own quote for the same town, option, payer and goods (free-delivery threshold applied); null when there is none
     * @param reason    why they differ, as typed
     * @return the reason to keep (trimmed), or null when none is needed
     * @throws SalesProblem 400 {@code delivery-reason-required}
     */
    public static String reasonFor(BigDecimal agreed, BigDecimal reference, String reason) {
        if (reference == null || agreed.compareTo(reference) == 0) {
            return null;
        }
        String why = reason == null ? "" : reason.strip();
        if (why.length() < REASON_MIN || why.length() > REASON_MAX) {
            throw new SalesProblem(HttpStatus.BAD_REQUEST, "delivery-reason-required",
                    "the agreed delivery (K" + agreed.toPlainString() + ") is not the shop's price for this town (K" + reference.toPlainString()
                            + "): say why, in " + REASON_MIN + " to " + REASON_MAX + " characters");
        }
        return why;
    }

    private static SalesProblem invalid(String detail) {
        return new SalesProblem(HttpStatus.BAD_REQUEST, "invalid-delivery", detail);
    }
}

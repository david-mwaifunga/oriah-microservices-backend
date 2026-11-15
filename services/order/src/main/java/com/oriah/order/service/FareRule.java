package com.oriah.order.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

import org.springframework.http.HttpStatus;

import com.oriah.money.Ngwee;
import com.oriah.order.exception.SalesProblem;
import com.oriah.quote.Quote;

/**
 * The courier fare Oriah pays, and when one is due (Phase 8, design D6). A pure rule: no Spring, no database.
 *
 * <p>The fare is a fact about the DISPATCH, not about the price the shopper was charged. Oriah charged the shopper (say K150.00) and a courier then
 * charged Oriah (say K100.00): the two are different money and the second is a cost of the month the parcel went. Whether a fare exists at all
 * depends on two things already fixed on the order when it was placed, the option the shopper chose and who pays the courier:
 *
 * <ul>
 *   <li>a courier Oriah pays (Yango, Platinum, EMS, a bus company's parcel service): a fare is due, and "K0.00" is a real answer (the courier
 *       charged nothing);</li>
 *   <li>a courier the recipient pays: the money never reaches Oriah, so it is neither income nor cost, and no fare is recorded
 *       (CLIENT_DECISIONS Q23: a cost of the month "except where the recipient paid");</li>
 *   <li>Oriah's own delivery: the delivery charge is income, and the fuel and the driver's pay are period expenses entered in finance, never a
 *       fare (CLIENT_DECISIONS section 1.3, Q27: no driver cash float);</li>
 *   <li>self-collect: there is no courier.</li>
 * </ul>
 *
 * It is keyed on the option and the payer and never on {@code shipping_amount}, which is K0.00 whenever delivery was free or waived and Oriah still
 * pays the courier.
 */
public final class FareRule {

    /** A typo guard (an extra zero), not a business limit: {@code oriah.sales.max-fare}, K5,000.00 unless set. */
    public static final BigDecimal DEFAULT_CEILING = new BigDecimal("5000.00");

    /** The code shipping seeds for Oriah's own delivery. */
    static final String OWN_DELIVERY = "OWN_DELIVERY";

    private FareRule() {
    }

    /** Whether the courier fare must be recorded when this order is dispatched. */
    public static boolean due(String optionCode, String payer) {
        return optionCode != null
                && !Quote.SELF_COLLECT.equals(optionCode)
                && !OWN_DELIVERY.equals(optionCode)
                && Quote.ORIAH_COLLECTS.equals(payer);
    }

    /**
     * @param farePaid what the packer typed: required when a fare is due, refused otherwise
     * @param ceiling  the largest fare accepted
     * @return the fare to keep, at two decimals; null when none is due and none was typed
     * @throws SalesProblem 409 {@code fare-required}, 409 {@code no-fare-for-this-delivery}, 400 {@code invalid-fare}
     */
    public static BigDecimal check(String optionCode, String payer, BigDecimal farePaid, BigDecimal ceiling) {
        if (!due(optionCode, payer)) {
            if (farePaid != null) {
                throw new SalesProblem(HttpStatus.CONFLICT, "no-fare-for-this-delivery", whyNoFare(optionCode, payer));
            }
            return null;
        }
        if (farePaid == null) {
            throw new SalesProblem(HttpStatus.CONFLICT, "fare-required",
                    "Oriah pays the courier on this order, so the fare the courier charged must be recorded (K0.00 if it charged nothing)");
        }
        if (!Ngwee.isWholeNgwee(farePaid) || farePaid.signum() < 0 || farePaid.compareTo(ceiling) > 0) {
            throw new SalesProblem(HttpStatus.BAD_REQUEST, "invalid-fare",
                    "the fare must be between K0.00 and K" + ceiling.toPlainString() + ", in whole ngwee");
        }
        return farePaid.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static String whyNoFare(String optionCode, String payer) {
        if (Quote.SELF_COLLECT.equals(optionCode)) {
            return "this order is collected by the shopper, so there is no courier fare";
        }
        if (OWN_DELIVERY.equals(optionCode)) {
            return "this is Oriah's own delivery: fuel and the driver's pay are entered as expenses in finance, not as a courier fare";
        }
        if (Quote.RECIPIENT_PAYS.equals(payer)) {
            return "the recipient pays the courier directly on this order, so Oriah has no courier fare to record";
        }
        return "no courier fare is due on this order";
    }
}

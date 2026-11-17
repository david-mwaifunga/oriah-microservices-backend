package com.oriah.payment.refund;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of money going back to customers ({@code oriah.refunds.*}).
 *
 * @param maxSendCharge the most a provider's charge for sending money back can be: a larger figure is a typing mistake
 * @param bearer        who bears that charge. For a refund the order system decides (it knows why the sale was corrected) and says so on the debt; for held money
 *                      payment decides here, from why the money was held
 */
@ConfigurationProperties(prefix = "oriah.refunds")
public record RefundSettings(BigDecimal maxSendCharge, Bearer bearer) {

    /** Why money is held, for each reason that can be paid back. WRONG_DATE is not here: such money is voided and keyed again, never paid back. */
    static final Set<String> HELD_REASONS = Set.of("WRONG_AMOUNT", "ALREADY_PAID", "ORDER_CANCELLED", "PIECE_SOLD", "ORDER_UNKNOWN");
    private static final Set<String> BEARERS = Set.of("CUSTOMER", "ORIAH");

    /** @param held who bears the sending charge when held money is paid back, by the reason it was held */
    public record Bearer(Map<String, String> held) {
    }

    /** A missing or misspelt setting refuses to start: who bears a charge is never guessed. */
    public RefundSettings {
        if (maxSendCharge == null || maxSendCharge.signum() < 0) {
            throw new IllegalStateException("oriah.refunds.max-send-charge must be set and not below nothing");
        }
        if (bearer == null || bearer.held() == null) {
            throw new IllegalStateException("oriah.refunds.bearer.held must say who bears the sending charge for each reason money is held");
        }
        for (String reason : HELD_REASONS) {
            String who = bearer.held().get(reason);
            if (who == null || !BEARERS.contains(who)) {
                throw new IllegalStateException("oriah.refunds.bearer.held." + reason + " must be CUSTOMER or ORIAH, was " + who);
            }
        }
    }

    /** Who bears the sending charge for held money of this reason; null for a reason that is never paid back. */
    public String bearerOfHeld(String reason) {
        return bearer.held().get(reason);
    }
}

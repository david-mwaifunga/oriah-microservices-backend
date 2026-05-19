package com.oriah.payment.gateway;

import java.math.BigDecimal;
import java.util.UUID;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Stripe-style magic-card mock. Sufficient to exercise the orchestration
 * + event flow without a real gateway.
 *
 * <ul>
 *   <li><b>4242 4242 4242 4242</b> &mdash; SUCCESS (Stripe's classic
 *       test card; same convention so tests are intuitive).</li>
 *   <li><b>4000 0000 0000 0002</b> &mdash; FAILURE: "card declined".</li>
 *   <li><b>4000 0000 0000 9995</b> &mdash; FAILURE: "insufficient funds".</li>
 *   <li>Anything else &mdash; FAILURE: "invalid test card".</li>
 * </ul>
 *
 * Whitespace is stripped before matching so {@code "4242 4242 4242 4242"}
 * and {@code "4242424242424242"} both work.
 */
@Component
@Slf4j
public class FakePaymentGateway implements PaymentGateway {

    private static final String CARD_SUCCESS = "4242424242424242";
    private static final String CARD_DECLINED = "4000000000000002";
    private static final String CARD_INSUFFICIENT = "4000000000009995";

    @Override
    public ChargeResult charge(String cardNumber, BigDecimal amount, String currency) {
        String normalized = cardNumber == null ? "" : cardNumber.replaceAll("\\s+", "");
        log.info("FakePaymentGateway.charge amount={} {} cardLast4={}",
                amount, currency, normalized.length() < 4 ? "????" : normalized.substring(normalized.length() - 4));
        return switch (normalized) {
            case CARD_SUCCESS -> new Success("fake_ch_" + UUID.randomUUID());
            case CARD_DECLINED -> new Failure("card declined");
            case CARD_INSUFFICIENT -> new Failure("insufficient funds");
            default -> new Failure("invalid test card");
        };
    }
}

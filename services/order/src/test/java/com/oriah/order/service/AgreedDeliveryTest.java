package com.oriah.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;

import com.oriah.order.exception.SalesProblem;

/**
 * A delivery agreed by phone or WhatsApp (Phase 9, design D5): the amount staff key as the order's delivery charge. A pure rule: it says what a
 * delivery agreed this way may be, and when staff must say why it differs from the shop's own quote. The charge is the order's fact and never changes
 * after the customer has committed (Q24); free delivery is the owner's or manager's waiver, never a key-in of K0.00 by whoever records the sale.
 */
class AgreedDeliveryTest {

    private static final BigDecimal CEILING = new BigDecimal("5000.00");

    private static BigDecimal check(String option, String payer, String amount) {
        return AgreedDelivery.check(option, payer, amount == null ? null : new BigDecimal(amount), CEILING);
    }

    private static void refused(String option, String payer, String amount, String code) {
        assertThatThrownBy(() -> check(option, payer, amount)).isInstanceOfSatisfying(SalesProblem.class, p -> {
            assertThat(p.status()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(p.code()).isEqualTo(code);
        });
    }

    @ParameterizedTest(name = "{0} paid by {1}: {2} is charged {3}")
    @CsvSource({
            "OWN_DELIVERY, ORIAH_COLLECTS, 60.00, 60.00",
            "YANGO, ORIAH_COLLECTS, 150, 150.00",
            "PLATINUM, ORIAH_COLLECTS, 0.01, 0.01",
            "EMS, ORIAH_COLLECTS, 5000.00, 5000.00",
            "YANGO, RECIPIENT_PAYS, 0.00, 0.00",
            "PLATINUM, RECIPIENT_PAYS, 0, 0.00",
    })
    @DisplayName("what an agreed delivery may be: Oriah's charge is a positive whole-ngwee amount up to the ceiling, a recipient-paid courier is K0.00 on the order")
    void whatIsAccepted(String option, String payer, String amount, String charged) {
        assertThat(check(option, payer, amount)).isEqualByComparingTo(charged).hasScaleOf(2);
    }

    @Test
    @DisplayName("a recipient-paid courier needs no amount: nothing typed is K0.00")
    void recipientPaysNeedsNoAmount() {
        assertThat(check("YANGO", "RECIPIENT_PAYS", null)).isEqualByComparingTo("0.00").hasScaleOf(2);
    }

    @ParameterizedTest(name = "{0} paid by {1} with {2} is refused")
    @CsvSource({
            "OWN_DELIVERY, ORIAH_COLLECTS, 0.00",
            "OWN_DELIVERY, ORIAH_COLLECTS, -1.00",
            "OWN_DELIVERY, ORIAH_COLLECTS, 5000.01",
            "OWN_DELIVERY, ORIAH_COLLECTS, 60.005",
            "YANGO, ORIAH_COLLECTS, 0.00",
            "YANGO, RECIPIENT_PAYS, 1.00",
            "YANGO, RECIPIENT_PAYS, -1.00",
            "OWN_DELIVERY, RECIPIENT_PAYS, 0.00",
            "OWN_DELIVERY, RECIPIENT_PAYS, 60.00",
            "SELF_COLLECT, ORIAH_COLLECTS, 0.00",
            "SELF_COLLECT, RECIPIENT_PAYS, 0.00",
            "YANGO, NOBODY, 100.00",
    })
    @DisplayName("what an agreed delivery may not be: zero or sub-ngwee or too large for Oriah to charge, any charge on a recipient-paid courier, own delivery paid by the recipient, a pickup, an unknown payer")
    void whatIsRefused(String option, String payer, String amount) {
        refused(option, payer, amount, "invalid-delivery");
    }

    @Test
    @DisplayName("an Oriah-collected delivery with no amount, and a missing option or payer, are refused")
    void nothingTyped() {
        refused("YANGO", "ORIAH_COLLECTS", null, "invalid-delivery");
        refused(null, "ORIAH_COLLECTS", "100.00", "invalid-delivery");
        refused("YANGO", null, "100.00", "invalid-delivery");
    }

    @Test
    @DisplayName("an agreed amount that equals the shop's own quote needs no reason; with no quote to compare to, none is needed either")
    void noReasonWhenItAgrees() {
        assertThat(AgreedDelivery.reasonFor(new BigDecimal("150.00"), new BigDecimal("150"), null)).isNull();
        assertThat(AgreedDelivery.reasonFor(new BigDecimal("150.00"), null, null)).isNull();
    }

    @Test
    @DisplayName("any difference from the shop's quote, higher or lower, needs a reason of 5 to 200 characters, kept trimmed; free delivery by threshold is a difference too")
    void aDifferenceNeedsAReason() {
        BigDecimal quote = new BigDecimal("150.00");
        for (String agreed : new String[] {"100.00", "150.01", "149.99", "400.00"}) {
            assertThatThrownBy(() -> AgreedDelivery.reasonFor(new BigDecimal(agreed), quote, " "))
                    .isInstanceOfSatisfying(SalesProblem.class, p -> {
                        assertThat(p.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                        assertThat(p.code()).isEqualTo("delivery-reason-required");
                    });
        }
        assertThatThrownBy(() -> AgreedDelivery.reasonFor(new BigDecimal("100.00"), quote, "four")).isInstanceOf(SalesProblem.class);
        assertThatThrownBy(() -> AgreedDelivery.reasonFor(new BigDecimal("100.00"), quote, "x".repeat(201))).isInstanceOf(SalesProblem.class);
        assertThat(AgreedDelivery.reasonFor(new BigDecimal("100.00"), quote, "  the caller is a regular and lives nearby ")).isEqualTo("the caller is a regular and lives nearby");
        assertThat(AgreedDelivery.reasonFor(new BigDecimal("100.00"), quote, "fiver")).isEqualTo("fiver");
        assertThat(AgreedDelivery.reasonFor(new BigDecimal("60.00"), BigDecimal.ZERO.setScale(2), "x".repeat(200))).hasSize(200);
    }
}

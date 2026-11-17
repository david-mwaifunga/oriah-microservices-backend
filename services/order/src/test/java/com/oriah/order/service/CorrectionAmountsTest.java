package com.oriah.order.service;

import com.oriah.order.service.CorrectionAmounts.Amounts;
import com.oriah.order.service.CorrectionAmounts.Line;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a customer is owed when a paid sale is undone (Phase 12, plan section 5), worked in Kwacha exactly as the plan's examples work it.
 */
class CorrectionAmountsTest {

    private static Line line(int quantity, String unitPrice, String discountPerPiece) {
        return new Line(UUID.randomUUID(), quantity, new BigDecimal(unitPrice), new BigDecimal(discountPerPiece));
    }

    private static BigDecimal k(String amount) {
        return new BigDecimal(amount);
    }

    /** Example 1: 2 rings at K100.00, K10.00 off each, own delivery K45.00; K225.00 was paid and K225.00 is owed. */
    @Test
    void aCancelledDeliveryOrderOwesItsPiecesAndItsDelivery() {
        Amounts owed = CorrectionAmounts.cancellation(List.of(line(2, "100.00", "10.00")), k("45.00"), null, k("225.00"));

        assertThat(owed.goodsAtList()).isEqualByComparingTo("200.00");
        assertThat(owed.discounts()).isEqualByComparingTo("20.00");
        assertThat(owed.deliveryRefunded()).isEqualByComparingTo("45.00");
        assertThat(owed.waiverTakenBack()).isEqualByComparingTo("0.00");
        assertThat(owed.owedGross()).isEqualByComparingTo("225.00");
        assertThat(owed.owedGross().scale()).isEqualTo(2);
    }

    /** Example 2: one of two rings comes back; K100.00 less its K10.00 promotion is owed, nothing for delivery and nothing for the box. */
    @Test
    void aReturnedPieceOwesItsOwnPriceLessItsOwnDiscount() {
        Amounts owed = CorrectionAmounts.ofReturn(List.of(line(1, "100.00", "10.00")));

        assertThat(owed.goodsAtList()).isEqualByComparingTo("100.00");
        assertThat(owed.discounts()).isEqualByComparingTo("10.00");
        assertThat(owed.deliveryRefunded()).isEqualByComparingTo("0.00");
        assertThat(owed.waiverTakenBack()).isEqualByComparingTo("0.00");
        assertThat(owed.owedGross()).isEqualByComparingTo("90.00");
    }

    /** Example 4: a watch at K1,250.00, K50.00 off, delivery agreed by phone at K60.00: the amount charged is owed, never the shop's quote. */
    @Test
    void anAgreedDeliveryIsOwedAtWhatWasCharged() {
        Amounts owed = CorrectionAmounts.cancellation(List.of(line(1, "1250.00", "50.00")), k("60.00"), null, k("1260.00"));

        assertThat(owed.owedGross()).isEqualByComparingTo("1260.00");
        assertThat(owed.deliveryRefunded()).isEqualByComparingTo("60.00");
    }

    /** The recipient paid the courier, a pickup, or free delivery by the threshold: delivery on the order is K0.00, so only the pieces are owed. */
    @Test
    void recipientPaidDeliveryOwesThePiecesOnly() {
        Amounts owed = CorrectionAmounts.cancellation(List.of(line(2, "100.00", "10.00")), k("0.00"), null, k("180.00"));

        assertThat(owed.deliveryRefunded()).isEqualByComparingTo("0.00");
        assertThat(owed.owedGross()).isEqualByComparingTo("180.00");
    }

    /** Delivery was waived (K45.00 given up): it was never paid, so it is not owed; the waiver is only taken back from the memo. */
    @Test
    void aWaivedDeliveryIsNotOwedAndItsMemoIsTakenBack() {
        Amounts owed = CorrectionAmounts.cancellation(List.of(line(1, "1250.00", "50.00")), k("0.00"), k("45.00"), k("1200.00"));

        assertThat(owed.deliveryRefunded()).isEqualByComparingTo("0.00");
        assertThat(owed.waiverTakenBack()).isEqualByComparingTo("45.00");
        assertThat(owed.owedGross()).isEqualByComparingTo("1200.00");
    }

    /** A promotion that took the whole price off a piece: K0.00 is owed for it, and the other line is owed in full. */
    @Test
    void aPieceThatCostNothingOwesNothing() {
        Amounts owed = CorrectionAmounts.ofReturn(List.of(line(1, "100.00", "100.00"), line(2, "30.00", "0.00")));

        assertThat(owed.goodsAtList()).isEqualByComparingTo("160.00");
        assertThat(owed.discounts()).isEqualByComparingTo("100.00");
        assertThat(owed.owedGross()).isEqualByComparingTo("60.00");
        assertThat(CorrectionAmounts.lineRefund(line(1, "100.00", "100.00"))).isEqualByComparingTo("0.00");
        assertThat(CorrectionAmounts.lineRefund(line(3, "100.00", "10.00"))).isEqualByComparingTo("270.00");
    }

    /** What is owed on a cancellation is what was paid: any other answer is a fault of ours and is never written down. */
    @Test
    void aCancellationThatDoesNotAddUpToTheOrdersTotalIsRefused() {
        assertThatThrownBy(() -> CorrectionAmounts.cancellation(List.of(line(2, "100.00", "10.00")), k("45.00"), null, k("224.99")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("225.00").hasMessageContaining("224.99");
    }

    /** A sale line from before discounts were stored per piece has none recorded: it is refused, never read as zero. */
    @Test
    void aLineWithNoDiscountRecordedIsNeverGuessed() {
        assertThatThrownBy(() -> CorrectionAmounts.ofReturn(List.of(new Line(UUID.randomUUID(), 1, k("100.00"), null))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CorrectionAmounts.ofReturn(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

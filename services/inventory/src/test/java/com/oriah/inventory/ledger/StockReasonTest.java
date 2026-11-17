package com.oriah.inventory.ledger;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every stock change carries a reason from ONE closed list, born here and complete from the start so that later
 * phases add handlers, not reasons. The database holds the same list in a CHECK; this test pins the two together.
 */
class StockReasonTest {

    @Test
    @DisplayName("the closed list, exactly: nothing is added by accident")
    void theClosedList() {
        assertThat(Arrays.stream(StockReason.values()).map(Enum::name)).containsExactlyInAnyOrder(
                "CONSIGNMENT_RECEIVED", "SALE", "RETURN", "WRITE_OFF_LOST", "WRITE_OFF_STOLEN", "WRITE_OFF_BROKEN",
                "GIVEN_AWAY_MODEL", "GIVEN_AWAY_INFLUENCER", "GIVEN_AWAY_GIFT", "GIVEN_AWAY_PRIZE", "TAKEN_BACK",
                "CANCELLED_BEFORE_DISPATCH", "RELISTED_MARKED_DOWN", "BOX_SWAPPED", "COST_CORRECTED", "WRITE_OFF_RETURNED");
    }

    @Test
    @DisplayName("a returned piece that cannot be sold is written off under a reason of its own (Phase 12), which the ordinary write-off door never takes")
    void aReturnedPieceIsWrittenOffUnderItsOwnReason() {
        assertThat(StockReason.WRITE_OFF_RETURNED.category()).isEqualTo(StockReason.Category.RETURNED_WRITE_OFF);
        assertThat(StockReason.WRITE_OFF_RETURNED.isWriteOffOrGiveAway())
                .as("it leaves through the assessment of a return, where the piece is known, never through the write-off door").isFalse();
    }

    @Test
    @DisplayName("each reason belongs to one category, which is what a report by reason groups on")
    void categories() {
        assertThat(StockReason.CONSIGNMENT_RECEIVED.category()).isEqualTo(StockReason.Category.RECEIPT);
        assertThat(StockReason.SALE.category()).isEqualTo(StockReason.Category.SALE);
        assertThat(List.of(StockReason.WRITE_OFF_LOST, StockReason.WRITE_OFF_STOLEN, StockReason.WRITE_OFF_BROKEN))
                .allMatch(r -> r.category() == StockReason.Category.WRITE_OFF);
        assertThat(List.of(StockReason.GIVEN_AWAY_MODEL, StockReason.GIVEN_AWAY_INFLUENCER, StockReason.GIVEN_AWAY_GIFT,
                StockReason.GIVEN_AWAY_PRIZE)).allMatch(r -> r.category() == StockReason.Category.GIVE_AWAY);
        assertThat(StockReason.TAKEN_BACK.category()).isEqualTo(StockReason.Category.TAKE_BACK);
        assertThat(StockReason.COST_CORRECTED.category()).isEqualTo(StockReason.Category.COST_CORRECTION);
    }

    @Test
    @DisplayName("a person may write off or give away only with one of those reasons: not a sale, not a correction")
    void whatTheWriteOffEndpointAccepts() {
        assertThat(Arrays.stream(StockReason.values()).filter(StockReason::isWriteOffOrGiveAway).map(Enum::name))
                .containsExactlyInAnyOrder("WRITE_OFF_LOST", "WRITE_OFF_STOLEN", "WRITE_OFF_BROKEN", "GIVEN_AWAY_MODEL",
                        "GIVEN_AWAY_INFLUENCER", "GIVEN_AWAY_GIFT", "GIVEN_AWAY_PRIZE");
        assertThat(StockReason.SALE.isWriteOffOrGiveAway()).isFalse();
        assertThat(StockReason.COST_CORRECTED.isWriteOffOrGiveAway()).isFalse();
    }

    @Test
    @DisplayName("an unknown code is refused with the list it should have been")
    void unknownCode() {
        assertThat(StockReason.fromCode("SALE")).isEqualTo(StockReason.SALE);
        assertThatThrownBy(() -> StockReason.fromCode("STOLEN_BY_ELVES"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("WRITE_OFF_STOLEN");
        assertThatThrownBy(() -> StockReason.fromCode(null)).isInstanceOf(IllegalArgumentException.class);
    }
}

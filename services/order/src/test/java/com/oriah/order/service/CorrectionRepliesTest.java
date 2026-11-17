package com.oriah.order.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which of inventory's answers PROVE it moved nothing (Phase 12, plan 4.2 and FINAL AM2). Only those free an order from a correction in flight; every
 * other answer may mean the stock went back, so the correction waits and is asked again.
 */
class CorrectionRepliesTest {

    @ParameterizedTest
    @CsvSource({"400,", "401,", "403,", "422,", "400,invalid-occurred-at", "404,sale-line-not-found", "404,reservation-not-found", "409,not-a-sale",
            "409,reservation-not-consumed", "409,box-line-not-live"})
    void theseProveNothingMoved(int status, String code) {
        assertThat(CorrectionReplies.provesNothingMoved(status, code == null ? "" : code)).isTrue();
    }

    /** An id inventory says it has used, or "more than is outstanding", is what a repeat of a reversal that DID happen can look like. */
    @ParameterizedTest
    @CsvSource({"409,reversal-id-used", "409,more-than-outstanding", "409,piece-id-used", "409,", "409,something-new", "404,", "404,not-found",
            "404,reversal-not-found", "408,", "425,", "429,", "500,", "502,", "503,", "504,", "-1,"})
    void theseProveNothing(int status, String code) {
        assertThat(CorrectionReplies.provesNothingMoved(status, code == null ? "" : code)).isFalse();
    }

    /** Reading a reversal back: only inventory's own typed 404 says it has none. A bare 404 can come from anything on the way. */
    @Test
    void onlyTheTypedNotFoundSaysThereIsNoReversal() {
        assertThat(CorrectionReplies.saysThereIsNoReversal(404, "reversal-not-found")).isTrue();
        assertThat(CorrectionReplies.saysThereIsNoReversal(404, "")).isFalse();
        assertThat(CorrectionReplies.saysThereIsNoReversal(404, "not-found")).isFalse();
        assertThat(CorrectionReplies.saysThereIsNoReversal(409, "reversal-not-found")).isFalse();
        assertThat(CorrectionReplies.saysThereIsNoReversal(503, "")).isFalse();
    }

    /** A returned piece's outcome: these refusals prove the piece is still waiting, so another outcome may be chosen. */
    @ParameterizedTest
    @CsvSource({"409,markdown-variant-of-another-product", "409,markdown-variant-in-use", "409,already-marked-down", "400,", "400,invalid-occurred-at",
            "401,", "403,", "422,"})
    void thesePutAPieceBackToWaiting(int status, String code) {
        assertThat(CorrectionReplies.provesThePieceWasNotDecided(status, code == null ? "" : code)).isTrue();
    }

    /** "The listing is not known yet" is a wait, a different outcome already recorded is a fault to look at: neither frees the piece. */
    @ParameterizedTest
    @CsvSource({"409,markdown-variant-not-known-yet", "409,piece-outcome-recorded", "404,returned-piece-not-found", "404,", "409,", "500,", "503,", "429,"})
    void theseLeaveAPieceInProgress(int status, String code) {
        assertThat(CorrectionReplies.provesThePieceWasNotDecided(status, code == null ? "" : code)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 9, 40})
    void theWaitDoublesFromFifteenSecondsToFiveMinutes(int attempts) {
        long seconds = CorrectionReplies.backoff(attempts).toSeconds();
        assertThat(seconds).isEqualTo(Math.min(300, 15L << Math.min(attempts, 10)));
    }
}

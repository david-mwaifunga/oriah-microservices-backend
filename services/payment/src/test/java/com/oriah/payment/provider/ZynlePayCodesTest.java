package com.oriah.payment.provider;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.oriah.payment.provider.ZynlePayCodes.PushAnswer;
import com.oriah.payment.provider.ZynlePayCodes.StatusAnswer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What each of ZynlePay's response codes means to Oriah. The meanings are known second-hand, so they are settings, and the rule that keeps money
 * safe is in the code: <strong>a code nobody listed is never a failure</strong>.
 */
class ZynlePayCodesTest {

    private static final ZynlePayCodes CODES = new ZynlePayCodes(List.of("120"), List.of("100"), List.of("990"), List.of("995"), List.of("01"),
            List.of("9900-9915"), List.of("02"), List.of("404"));

    @Test
    @DisplayName("at the push: 120 is 'initiated', a listed refusal is a refusal, and anything unlisted is unknown, never a refusal")
    void push() {
        assertThat(CODES.push("120")).isEqualTo(PushAnswer.INITIATED);
        assertThat(CODES.push("01")).isEqualTo(PushAnswer.REFUSED);
        assertThat(CODES.push("9900")).isEqualTo(PushAnswer.CREDENTIALS);
        assertThat(CODES.push("9907")).isEqualTo(PushAnswer.CREDENTIALS);
        assertThat(CODES.push("9915")).isEqualTo(PushAnswer.CREDENTIALS);
        assertThat(CODES.push("02")).isEqualTo(PushAnswer.AMBIGUOUS);
        assertThat(CODES.push("777")).isEqualTo(PushAnswer.UNKNOWN);
        assertThat(CODES.push("9916")).isEqualTo(PushAnswer.UNKNOWN);
        assertThat(CODES.push(null)).isEqualTo(PushAnswer.UNKNOWN);
        assertThat(CODES.push("  ")).isEqualTo(PushAnswer.UNKNOWN);
        // What means "failed" to the status call means nothing at the push: only the refusal list refuses.
        assertThat(CODES.push("995")).isEqualTo(PushAnswer.UNKNOWN);
        // "paid" at the push would be a claim nobody checked: it is not "initiated" and it is not money.
        assertThat(CODES.push("100")).isEqualTo(PushAnswer.UNKNOWN);
    }

    @Test
    @DisplayName("at the status call: 100 paid, 990 pending, 995 failed; anything unlisted is unreadable, never failed and never paid")
    void status() {
        assertThat(CODES.status("100")).isEqualTo(StatusAnswer.PAID);
        assertThat(CODES.status(" 100 ")).isEqualTo(StatusAnswer.PAID);
        assertThat(CODES.status("990")).isEqualTo(StatusAnswer.PENDING);
        assertThat(CODES.status("995")).isEqualTo(StatusAnswer.FAILED);
        assertThat(CODES.status("404")).isEqualTo(StatusAnswer.NOT_FOUND);
        assertThat(CODES.status("9901")).isEqualTo(StatusAnswer.CREDENTIALS);
        assertThat(CODES.status("120")).isEqualTo(StatusAnswer.PENDING);       // still only initiated
        assertThat(CODES.status("01")).isEqualTo(StatusAnswer.UNREADABLE);     // a push refusal code is not a status
        assertThat(CODES.status("1000")).isEqualTo(StatusAnswer.UNREADABLE);
        assertThat(CODES.status(null)).isEqualTo(StatusAnswer.UNREADABLE);
    }

    @Test
    @DisplayName("one code cannot mean two things: lists that overlap are refused when they are read")
    void overlappingListsAreRefused() {
        assertThatThrownBy(() -> new ZynlePayCodes(List.of("120"), List.of("100"), List.of("990"), List.of("995"), List.of("9907"), List.of("9900-9915"),
                List.of(), List.of())).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("9907");
        assertThatThrownBy(() -> new ZynlePayCodes(List.of("120"), List.of("100"), List.of("100"), List.of("995"), List.of(), List.of(), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("100");
    }

    @Test
    @DisplayName("the three codes the money rests on must each be given, and a code is digits only")
    void theCodesMoneyRestsOnAreRequired() {
        assertThatThrownBy(() -> new ZynlePayCodes(List.of("120"), List.of(), List.of("990"), List.of("995"), List.of(), List.of(), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("paid");
        assertThatThrownBy(() -> new ZynlePayCodes(List.of(), List.of("100"), List.of("990"), List.of("995"), List.of(), List.of(), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("initiated");
        assertThatThrownBy(() -> new ZynlePayCodes(List.of("120"), List.of("100"), List.of("990"), List.of(), List.of(), List.of(), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("failed");
        assertThatThrownBy(() -> new ZynlePayCodes(List.of("12O"), List.of("100"), List.of("990"), List.of("995"), List.of(), List.of(), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("12O");
        assertThatThrownBy(() -> new ZynlePayCodes(List.of("120"), List.of("100"), List.of("990"), List.of("995"), List.of(), List.of("9915-9900"), List.of(),
                List.of())).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("9915-9900");
    }
}

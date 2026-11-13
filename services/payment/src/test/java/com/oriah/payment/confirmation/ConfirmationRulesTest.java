package com.oriah.payment.confirmation;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The small rules behind confirming money (Phase 7): how a reference is spelled so that two spellings of one payment collide, which methods need
 * one, and which dates money may be said to have arrived on and by whom (anyone who may confirm, within 72 hours; past that the owner, with a
 * reason: a sale recorded long after it happened is a decision, not a keystroke).
 */
class ConfirmationRulesTest {

    private static final Instant NOW = Instant.parse("2026-11-14T08:00:00Z");
    private static final Duration WINDOW = Duration.ofHours(72);

    // ---------------------------------------------------------------- references

    @Test
    @DisplayName("a reference is trimmed, upper-cased and stripped of every space, so 'mp 2411 abc' and ' MP2411ABC ' are one payment")
    void spellings() {
        assertThat(ReferenceNumber.normalise(" mp 2411 abc ")).isEqualTo("MP2411ABC");
        assertThat(ReferenceNumber.normalise("MP2411ABC")).isEqualTo("MP2411ABC");
        assertThat(ReferenceNumber.normalise("mp\t2411\n abc")).isEqualTo("MP2411ABC");
        assertThat(ReferenceNumber.normalise("mp.2411-ab/c_1")).isEqualTo("MP.2411-AB/C_1");
    }

    @Test
    @DisplayName("full-width letters and digits read as the ordinary ones")
    void lookalikes() {
        assertThat(ReferenceNumber.normalise("ＭＰ１２３")).isEqualTo("MP123");
    }

    @Test
    @DisplayName("a reference is upper-cased the same wherever the server runs: on a machine whose language is Turkish an 'i' is still an 'I' (not a dotted capital that would make two spellings of one payment differ)")
    void theLocaleOfTheServerDoesNotMatter() {
        java.util.Locale before = java.util.Locale.getDefault();
        java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"));
        try {
            assertThat("ref-iii".toUpperCase()).as("the trap is real here: this JVM's own upper-casing is Turkish now").isEqualTo("REF-İİİ");
            assertThat(ReferenceNumber.normalise("ref-iii")).isEqualTo("REF-III");
            assertThat(ReferenceNumber.normalise("refı")).as("a dotless i is an ordinary I").isEqualTo("REFI");
        } finally {
            java.util.Locale.setDefault(before);
        }
    }

    @Test
    @DisplayName("a reference that is empty, too short, too long or made of anything but letters, digits and . _ - / is refused")
    void refused() {
        assertThatThrownBy(() -> ReferenceNumber.normalise(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReferenceNumber.normalise("   ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReferenceNumber.normalise("ab")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("3");
        assertThatThrownBy(() -> ReferenceNumber.normalise("A".repeat(65))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("64");
        assertThatThrownBy(() -> ReferenceNumber.normalise("MP#123")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReferenceNumber.normalise("MP​123")).as("a zero-width space is not a space and not a character").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReferenceNumber.normalise("MP🙂123")).isInstanceOf(IllegalArgumentException.class);
        assertThat(ReferenceNumber.normalise("abc")).isEqualTo("ABC");
        assertThat(ReferenceNumber.normalise("A".repeat(64))).hasSize(64);
    }

    // ---------------------------------------------------------------- methods

    @Test
    @DisplayName("a method is read whatever its case; unknown ones are refused; cash is the only one that needs no reference")
    void methods() {
        assertThat(PaymentMethod.parse("mtn")).isEqualTo(PaymentMethod.MTN);
        assertThat(PaymentMethod.parse(" Bank_Transfer ")).isEqualTo(PaymentMethod.BANK_TRANSFER);
        assertThatThrownBy(() -> PaymentMethod.parse("BITCOIN")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("CASH");
        assertThatThrownBy(() -> PaymentMethod.parse(null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(PaymentMethod.CASH.requiresReference()).isFalse();
        for (PaymentMethod method : PaymentMethod.values()) {
            if (method != PaymentMethod.CASH) {
                assertThat(method.requiresReference()).as(method.name()).isTrue();
            }
        }
    }

    // ---------------------------------------------------------------- the arrival window

    @Test
    @DisplayName("money that arrived within 72 hours (the boundary included) needs no authority and no reason")
    void withinTheWindow() {
        assertThat(ArrivalWindow.check(NOW.minusSeconds(60), NOW, WINDOW, false, null)).isNull();
        assertThat(ArrivalWindow.check(NOW.minus(WINDOW), NOW, WINDOW, false, null)).as("exactly 72 hours").isNull();
        assertThat(ArrivalWindow.check(NOW.minus(Duration.ofHours(1)), NOW, WINDOW, false, "typed anyway")).as("a reason is not kept when none was needed").isNull();
    }

    @Test
    @DisplayName("older than 72 hours by even a moment, someone without BACKDATE_SALE is refused (403), reason or not")
    void tooOldWithoutTheAuthority() {
        Instant late = NOW.minus(WINDOW).minusMillis(1);

        assertThatThrownBy(() -> ArrivalWindow.check(late, NOW, WINDOW, false, null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> ArrivalWindow.check(late, NOW, WINDOW, false, "customer paid on Friday and I was away")).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("older than 72 hours with BACKDATE_SALE needs a reason of 5 to 200 characters, and the kept reason is trimmed")
    void tooOldWithTheAuthority() {
        Instant late = NOW.minus(Duration.ofDays(4));

        assertThatThrownBy(() -> ArrivalWindow.check(late, NOW, WINDOW, true, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ArrivalWindow.check(late, NOW, WINDOW, true, "   ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ArrivalWindow.check(late, NOW, WINDOW, true, "abcd")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ArrivalWindow.check(late, NOW, WINDOW, true, "x".repeat(201))).isInstanceOf(IllegalArgumentException.class);
        assertThat(ArrivalWindow.check(late, NOW, WINDOW, true, "  paid by transfer on Friday  ")).isEqualTo("paid by transfer on Friday");
        assertThat(ArrivalWindow.check(late, NOW, WINDOW, true, "abcde")).isEqualTo("abcde");
        assertThat(ArrivalWindow.check(late, NOW, WINDOW, true, "x".repeat(200))).hasSize(200);
    }

    @Test
    @DisplayName("money cannot arrive in the future: a few seconds ahead is clock skew and fine, a minute ahead is a mistake (400)")
    void notInTheFuture() {
        assertThat(ArrivalWindow.check(NOW.plusSeconds(10), NOW, WINDOW, false, null)).isNull();
        assertThatThrownBy(() -> ArrivalWindow.check(NOW.plusSeconds(60), NOW, WINDOW, true, "reason here")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("future");
    }
}

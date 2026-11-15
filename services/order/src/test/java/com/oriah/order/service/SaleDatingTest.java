package com.oriah.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;

import com.oriah.order.exception.SalesProblem;
import com.oriah.order.service.SaleDating.Dated;
import com.oriah.order.model.SaleKind;

/**
 * The date a staff-recorded sale is made at, and who may say it (Phase 9, design r4 section A). Two kinds, chosen by the person on the form:
 *
 * <ul>
 *   <li>{@code LIVE_PHONE}: the sale is happening now. The date is now and cannot be typed.</li>
 *   <li>{@code LATE_PAPER}: a sale written on paper and typed in later. The date is typed, of any age, and a reason is ALWAYS required (a historical price
 *       and promotion are whatever the day's were, so a sale keyed after the fact needs words behind it). Beyond the 72 hours of CLIENT_DECISIONS 8.2 only
 *       someone who may back-date a sale can key it. The window is the one setting payment also reads.</li>
 * </ul>
 *
 * A plain unit test: the date is the one fact the books are built on, so every edge is tried here.
 */
class SaleDatingTest {

    private static final Instant NOW = Instant.parse("2026-11-20T10:00:00Z");
    private static final Duration WINDOW = Duration.ofHours(72);
    private static final String REASON = "the paper note was found in the shop drawer";

    private static Dated live(Instant typed) {
        return SaleDating.check(SaleKind.LIVE_PHONE, typed, NOW, WINDOW, false, null);
    }

    private static Dated late(Instant typed, boolean mayBackdate, String reason) {
        return SaleDating.check(SaleKind.LATE_PAPER, typed, NOW, WINDOW, mayBackdate, reason);
    }

    private static void refusedAs(Runnable call, HttpStatus status, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(SalesProblem.class, p -> {
            assertThat(p.status()).isEqualTo(status);
            assertThat(p.code()).isEqualTo(code);
        });
    }

    @Test
    @DisplayName("a live sale is dated now, to the microsecond the database keeps, with no reason")
    void aLiveSaleIsNow() {
        Dated d = SaleDating.check(SaleKind.LIVE_PHONE, null, Instant.parse("2026-11-20T10:00:00.123456789Z"), WINDOW, false, null);

        assertThat(d.occurredAt()).isEqualTo(Instant.parse("2026-11-20T10:00:00.123456Z"));
        assertThat(d.reason()).isNull();
    }

    @Test
    @DisplayName("a live sale cannot be given a date: one is 400 invalid-occurred-at, even one that is now")
    void aLiveSaleTakesNoDate() {
        refusedAs(() -> live(NOW.minusSeconds(60)), HttpStatus.BAD_REQUEST, "invalid-occurred-at");
        refusedAs(() -> live(NOW), HttpStatus.BAD_REQUEST, "invalid-occurred-at");
    }

    @Test
    @DisplayName("a late paper sale must say when it happened: nothing typed is 400 invalid-occurred-at")
    void aLateSaleNeedsItsDate() {
        refusedAs(() -> late(null, true, REASON), HttpStatus.BAD_REQUEST, "invalid-occurred-at");
    }

    @Test
    @DisplayName("a late paper sale inside the window is kept at its date with its reason, by anyone who may record a sale")
    void insideTheWindow() {
        Instant yesterday = NOW.minus(Duration.ofHours(30));

        Dated d = late(yesterday, false, "  " + REASON + "  ");

        assertThat(d.occurredAt()).isEqualTo(yesterday);
        assertThat(d.reason()).as("trimmed").isEqualTo(REASON);
    }

    @Test
    @DisplayName("a late paper sale needs a reason of 5 to 200 characters even inside the window, and even a minute ago: nothing is kept without one")
    void aReasonIsAlwaysRequired() {
        Instant recent = NOW.minus(Duration.ofMinutes(20));
        for (String reason : new String[] {null, "", "    ", "four"}) {
            refusedAs(() -> late(recent, true, reason), HttpStatus.BAD_REQUEST, "backdate-reason-required");
        }
        refusedAs(() -> late(recent, true, "x".repeat(201)), HttpStatus.BAD_REQUEST, "backdate-reason-required");
        assertThat(late(recent, false, "fiver").reason()).isEqualTo("fiver");
        assertThat(late(recent, false, "x".repeat(200)).reason()).hasSize(200);
    }

    @Test
    @DisplayName("exactly 72 hours back is still inside the window; a microsecond more needs the back-dating capability")
    void theWindowBoundary() {
        Instant edge = NOW.minus(WINDOW);

        assertThat(late(edge, false, REASON).occurredAt()).isEqualTo(edge);
        assertThatThrownBy(() -> late(edge.minusNanos(1000), false, REASON)).isInstanceOf(AccessDeniedException.class);
        assertThat(late(edge.minusNanos(1000), true, REASON).occurredAt()).isEqualTo(edge.minusNanos(1000));
    }

    @Test
    @DisplayName("beyond the window the capability alone is not enough: the reason is still required (400), and it is judged after the capability (403 first)")
    void beyondTheWindow() {
        Instant old = NOW.minus(Duration.ofDays(10));

        assertThatThrownBy(() -> late(old, false, REASON)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> late(old, false, null)).as("403, not 400: who may key it comes first").isInstanceOf(AccessDeniedException.class);
        refusedAs(() -> late(old, true, null), HttpStatus.BAD_REQUEST, "backdate-reason-required");
        assertThat(late(old, true, REASON).occurredAt()).isEqualTo(old);
    }

    @Test
    @DisplayName("a date in the future is 400 invalid-occurred-at beyond five minutes' clock skew; inside it the date is kept as typed")
    void theFuture() {
        assertThat(late(NOW.plus(Duration.ofMinutes(5)), false, REASON).occurredAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
        refusedAs(() -> late(NOW.plus(Duration.ofMinutes(5)).plusNanos(1000), true, REASON), HttpStatus.BAD_REQUEST, "invalid-occurred-at");
    }

    @Test
    @DisplayName("the date is judged to the microsecond the database keeps, so the date an event reports is exactly the date the row holds")
    void microseconds() {
        Dated d = late(Instant.parse("2026-11-19T09:00:00.123456789Z"), false, REASON);

        assertThat(d.occurredAt()).isEqualTo(Instant.parse("2026-11-19T09:00:00.123456Z"));
    }
}

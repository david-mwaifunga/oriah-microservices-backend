package com.oriah.notification.alerts;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * When staff are reminded that a customer's claimed payment is still not confirmed.
 *
 * <p>The rule: {@code remindBefore} (three hours) before the pieces are released. A reminder that would fall in the night ({@code quietFrom} to
 * {@code quietUntil} on the shop's clock, eight in the evening to seven in the morning) is moved BACK to the evening before, because earlier
 * leaves time to act and nobody reads an email at three in the morning. When that evening has already passed (the customer claimed late at
 * night), it goes at {@code quietUntil} in the morning instead, if the hold still has half an hour to run then. Otherwise there is none.
 *
 * <p>No reminder either when under {@code remindBefore} plus half an hour remains: staff were told of the claim at once, moments ago.
 *
 * @param zone the shop's zone: "night" is night in Lusaka, not on the server
 */
public record HoldRemindTime(Duration remindBefore, LocalTime quietFrom, LocalTime quietUntil, ZoneId zone) {

    /** The least time a reminder must leave staff to act on it, and the least gap after the first notice. */
    private static final Duration WORTH_IT = Duration.ofMinutes(30);

    /**
     * @param holdExpiresAt when the pieces are released
     * @param now           the moment the claim is being handled
     * @return when to remind, strictly after {@code now}; empty when no reminder is worth sending
     */
    public Optional<Instant> of(Instant holdExpiresAt, Instant now) {
        if (Duration.between(now, holdExpiresAt).compareTo(remindBefore.plus(WORTH_IT)) < 0) {
            return Optional.empty();
        }
        ZonedDateTime wanted = holdExpiresAt.minus(remindBefore).atZone(zone);
        LocalTime onTheShopsClock = wanted.toLocalTime();
        if (!isNight(onTheShopsClock)) {
            return Optional.of(wanted.toInstant());
        }
        // The night a time belongs to began the evening before when the time is after midnight, and the same evening otherwise.
        boolean afterMidnight = onTheShopsClock.isBefore(quietFrom);
        Instant eveningBefore = (afterMidnight ? wanted.minusDays(1) : wanted).with(quietFrom).toInstant();
        if (eveningBefore.isAfter(now)) {
            return Optional.of(eveningBefore);
        }
        Instant morningAfter = (afterMidnight ? wanted : wanted.plusDays(1)).with(quietUntil).toInstant();
        if (!morningAfter.plus(WORTH_IT).isAfter(holdExpiresAt)) {
            return Optional.of(morningAfter);
        }
        return Optional.empty();
    }

    private boolean isNight(LocalTime time) {
        if (quietFrom.isAfter(quietUntil)) {
            // The usual case: the quiet hours run over midnight.
            return !time.isBefore(quietFrom) || time.isBefore(quietUntil);
        }
        return !time.isBefore(quietFrom) && time.isBefore(quietUntil);
    }
}

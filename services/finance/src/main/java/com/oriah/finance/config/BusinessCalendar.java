package com.oriah.finance.config;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;

import org.springframework.stereotype.Component;

/**
 * The ONE place an instant becomes a day or a month of the shop: Lusaka's calendar (UTC+2, no daylight saving). A sale paid at 23:30 UTC on 31 March happened on 1
 * April in Lusaka and counts in April; a month's profit is a count of Lusaka days. Nothing else in the service converts an instant to a date.
 */
@Component
public class BusinessCalendar {

    public static final ZoneId ZONE = ZoneId.of("Africa/Lusaka");

    /** The Lusaka day an instant falls on. */
    public LocalDate dayOf(Instant instant) {
        return instant.atZone(ZONE).toLocalDate();
    }

    /** The first day of the Lusaka month an instant falls in. */
    public LocalDate monthOf(Instant instant) {
        return firstOfMonth(dayOf(instant));
    }

    /** The first day of the month a day falls in. */
    public static LocalDate firstOfMonth(LocalDate day) {
        return day.withDayOfMonth(1);
    }

    /** The instant a Lusaka month begins. */
    public Instant startOf(YearMonth month) {
        return month.atDay(1).atStartOfDay(ZONE).toInstant();
    }

    /** The instant the NEXT Lusaka month begins: the month ends the moment before it. */
    public Instant endOf(YearMonth month) {
        return month.plusMonths(1).atDay(1).atStartOfDay(ZONE).toInstant();
    }

    /** Lusaka's today. */
    public LocalDate today(Instant now) {
        return dayOf(now);
    }
}

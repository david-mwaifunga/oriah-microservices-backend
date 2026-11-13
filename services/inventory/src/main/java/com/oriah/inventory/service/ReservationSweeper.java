package com.oriah.inventory.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Ends the holds whose time has passed. Nothing but a clock ends a hold nobody sells or releases, so this looks every half minute (the
 * interval is {@code oriah.inventory.reservations.sweep.interval}) and ends a batch at a time, each hold in a transaction of its own, so
 * one that is busy or broken does not hold the others up. It runs in every instance: a hold being sold at that moment is skipped (its row
 * is locked), and a hold two instances both reach is ended by whichever locks it first.
 *
 * <p>It shares the scheduler with the outbox relay; {@code spring.task.scheduling.pool.size} is 2 so a relay round blocked on a stopped
 * Kafka cannot delay a hold's expiry. Scheduling is enabled here as well as by the event module, so switching the relay off cannot
 * silently stop the sweep.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "oriah.inventory.reservations.sweep.enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
public class ReservationSweeper {

    private final ReservationService reservations;
    private final int batch;

    public ReservationSweeper(ReservationService reservations, @Value("${oriah.inventory.reservations.sweep.batch:50}") int batch) {
        this.reservations = reservations;
        this.batch = batch;
    }

    /** One sweep: ends up to a batch of lapsed holds and says how many. */
    public int sweep() {
        int ended = reservations.expireDue(batch);
        if (ended > 0) {
            log.info("Reservation sweep ended {} lapsed hold(s)", ended);
        }
        return ended;
    }

    /** What the scheduler runs. A failure is logged and the next sweep tries again; it never stops the schedule. */
    @Scheduled(fixedDelayString = "${oriah.inventory.reservations.sweep.interval:PT30S}")
    public void scheduled() {
        try {
            sweep();
        } catch (RuntimeException e) {
            log.error("Reservation sweep failed; the next one tries again", e);
        }
    }
}

package com.oriah.procurement.service;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.oriah.procurement.exception.ServerBusyException;

/**
 * How many invoices are taken in at the same moment.
 *
 * <p>A 5 MB upload is held in memory about three times over while it is read, fingerprinted and written
 * (roughly 15 MB), and this service's heap is small (217 MiB of heap in a 768 MiB container). Measured live,
 * sixteen at once left half of them a 500 and the container at 96% of its limit. So only
 * {@code oriah.procurement.uploads.max-concurrent} (default 2: 30 MB at the worst) run together; the rest wait
 * up to {@code max-wait} (default 20 s) for a place, and if none comes they are told to try again ({@link
 * ServerBusyException}: 503 with {@code Retry-After}), never failed half-way. Catalog's photographs are held
 * the same way ({@code IngestGate}).
 *
 * <p>Nothing about the work is known here: it is whatever reads the file and stores it. The place is given
 * back however the work ends.
 */
@Component
public class UploadGate {

    /** What runs inside the gate. */
    @FunctionalInterface
    public interface Work<T> {
        T run() throws Exception;
    }

    private final Semaphore places;
    private final Duration maxWait;

    public UploadGate(@Value("${oriah.procurement.uploads.max-concurrent:2}") int maxConcurrent,
                      @Value("${oriah.procurement.uploads.max-wait:20s}") Duration maxWait) {
        if (maxConcurrent < 1) {
            throw new IllegalArgumentException("oriah.procurement.uploads.max-concurrent must be at least 1, was " + maxConcurrent);
        }
        if (maxWait.isNegative()) {
            throw new IllegalArgumentException("oriah.procurement.uploads.max-wait cannot be negative, was " + maxWait);
        }
        this.places = new Semaphore(maxConcurrent, true);
        this.maxWait = maxWait;
    }

    /** Runs {@code work} when there is room, waiting up to the maximum for it. */
    public <T> T run(Work<T> work) throws Exception {
        boolean got;
        try {
            got = places.tryAcquire(maxWait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServerBusyException();
        }
        if (!got) {
            throw new ServerBusyException();
        }
        try {
            return work.run();
        } finally {
            places.release();
        }
    }
}

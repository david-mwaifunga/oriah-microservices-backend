package com.oriah.catalog.storage;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

/**
 * Limits how much photograph catalog works on at the same time, so a burst of large uploads
 * costs the admin a retry and not the service its life.
 *
 * <h2>Why a limit is needed at all</h2>
 *
 * An upload is decoded into a full raster, turned upright into a second one, and re-encoded.
 * The biggest picture the ingest accepts (8000 x 5000, 40 megapixels) therefore needs about
 * 300 MiB of heap while it is worked on. Catalog's heap is 575 MiB, and the web server
 * would happily run fifty of those at once: 124 at once killed the service with
 * {@code OutOfMemoryError} (measured 2026-11-11 with {@code scripts/ingest-load-test.ps1}).
 *
 * <h2>How it works</h2>
 *
 * A fixed budget of working memory, in MiB. Each upload asks for what its picture needs,
 * 8 bytes a pixel ({@link #workingMemoryMiB}): the decoded raster is 3-4 bytes a pixel and
 * the upright copy 4. While memory is free the upload goes straight in; otherwise it waits,
 * up to {@code max-wait}, in a queue that is itself limited to {@code max-waiting}. An upload
 * that cannot be served gets {@link ImageIngestBusyException}: 503 with {@code Retry-After}.
 * Waiting is first come, first served, so a big picture is not starved by small ones.
 *
 * <p>The budget is the number to keep in step with the heap: 400 MiB is 70% of 575 MiB, which
 * leaves the rest of the service about 175 MiB beside it. A budget that does not even cover
 * the biggest picture is refused when the service starts.
 *
 * <h2>Where it sits</h2>
 *
 * Outside the database transaction. An upload that waits here holds no connection, so
 * a queue of admin uploads cannot starve the storefront's reads
 * ({@code ProductImageGateTest} pins that).
 */
@Component
@Slf4j
public class IngestGate {

    /** Hands out working memory for decoding a photograph. */
    @FunctionalInterface
    public interface Admission {
        /**
         * @param pixels width times height of the picture, from its header
         * @return a permit to give back when the picture has been worked on
         * @throws ImageIngestBusyException when there is no memory to spare
         */
        Permit admit(long pixels);
    }

    /** Memory held for one upload; closing it gives it back (closing twice is harmless). */
    @FunctionalInterface
    public interface Permit extends AutoCloseable {
        @Override
        void close();
    }

    /** Decoded raster (3-4 bytes a pixel) plus the upright copy (4): 8, a little over. */
    static final long BYTES_PER_PIXEL = 8;

    private static final long MIB = 1024 * 1024;

    private final int budgetMiB;
    private final Semaphore memory;
    private final int maxWaiting;
    private final long maxWaitNanos;
    private final AtomicInteger waiting = new AtomicInteger();

    public IngestGate(
            @Value("${catalog.images.ingest.working-memory-mib:400}") long workingMemoryMiB,
            @Value("${catalog.images.ingest.max-waiting:4}") int maxWaiting,
            @Value("${catalog.images.ingest.max-wait:20s}") Duration maxWait) {
        long biggest = workingMemoryMiB(ProductImageIngest.MAX_PIXELS);
        if (workingMemoryMiB < biggest) {
            throw new IllegalArgumentException(
                    "catalog.images.ingest.working-memory-mib is " + workingMemoryMiB
                            + " but the biggest picture catalog accepts ("
                            + ProductImageIngest.MAX_PIXELS + " pixels) needs " + biggest
                            + " MiB; no upload of that size could ever be served");
        }
        if (workingMemoryMiB > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("catalog.images.ingest.working-memory-mib is absurdly large");
        }
        if (maxWaiting < 0) {
            throw new IllegalArgumentException("catalog.images.ingest.max-waiting must not be negative");
        }
        if (maxWait == null || maxWait.isZero() || maxWait.isNegative()) {
            throw new IllegalArgumentException("catalog.images.ingest.max-wait must be a positive duration");
        }
        this.budgetMiB = (int) workingMemoryMiB;
        // fair: whoever has waited longest is served first
        this.memory = new Semaphore(budgetMiB, true);
        this.maxWaiting = maxWaiting;
        this.maxWaitNanos = maxWait.toNanos();
    }

    /** What a picture of this many pixels is charged, in whole MiB (never less than 1). */
    public static long workingMemoryMiB(long pixels) {
        long bytes = pixels * BYTES_PER_PIXEL;
        return Math.max(1, (bytes + MIB - 1) / MIB);
    }

    /**
     * Waits for working memory for a picture of this many pixels.
     *
     * @throws ImageIngestBusyException when too many are already waiting, or the wait
     *         runs out, or the thread is interrupted (its interrupt flag is kept)
     */
    public Permit admit(long pixels) {
        if (pixels <= 0) {
            throw new IllegalArgumentException("a picture has at least one pixel");
        }
        long need = workingMemoryMiB(pixels);
        if (need > budgetMiB) {
            throw new IllegalArgumentException(
                    "a picture of " + pixels + " pixels needs " + need + " MiB, more than the budget of " + budgetMiB);
        }
        int permits = (int) need;

        try {
            // fair, zero wait: takes the memory if it is free and nobody is ahead of us
            if (memory.tryAcquire(permits, 0, TimeUnit.NANOSECONDS)) {
                return new Held(permits);
            }
            if (waiting.incrementAndGet() > maxWaiting) {
                waiting.decrementAndGet();
                log.warn("Photograph processing is full: {} waiting already, refusing a {}-pixel picture",
                        maxWaiting, pixels);
                throw new ImageIngestBusyException();
            }
            try {
                if (memory.tryAcquire(permits, maxWaitNanos, TimeUnit.NANOSECONDS)) {
                    return new Held(permits);
                }
            } finally {
                waiting.decrementAndGet();
            }
            log.warn("Photograph processing is busy: gave up after {} ms waiting for {} MiB",
                    TimeUnit.NANOSECONDS.toMillis(maxWaitNanos), permits);
            throw new ImageIngestBusyException();
        } catch (InterruptedException e) {
            // the thread was told to stop (shutdown): keep that, and do not take memory
            Thread.currentThread().interrupt();
            throw new ImageIngestBusyException();
        }
    }

    /** MiB of working memory free right now. */
    public long availableMiB() {
        return memory.availablePermits();
    }

    /** Uploads waiting for memory right now. */
    public int waiting() {
        return waiting.get();
    }

    private final class Held implements Permit {
        private final int mib;
        private final AtomicBoolean closed = new AtomicBoolean();

        Held(int mib) {
            this.mib = mib;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                memory.release(mib);
            }
        }
    }
}

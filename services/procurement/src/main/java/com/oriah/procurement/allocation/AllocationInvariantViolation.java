package com.oriah.procurement.allocation;

/**
 * The allocator's own arithmetic did not add up: units that cost more or less
 * than was spent.
 *
 * <p>Deliberately NOT an {@code IllegalArgumentException} or {@code
 * IllegalStateException}: the shared error contract renders those as a 400 or a
 * 409, which blame the caller. This is our bug, money that would leak out of the
 * books, and it must reach the 500 catch-all where it is logged as an error and
 * noticed.
 */
public final class AllocationInvariantViolation extends RuntimeException {

    public AllocationInvariantViolation(String message) {
        super(message);
    }
}

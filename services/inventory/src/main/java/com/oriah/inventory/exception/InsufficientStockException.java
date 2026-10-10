package com.oriah.inventory.exception;

/**
 * A stock operation conflicts with the current state of the aggregate —
 * reserving more than is available, or releasing or confirming more than is
 * reserved.
 *
 * <h2>Why this extends {@link IllegalStateException}</h2>
 *
 * <p>This is the mechanism that makes per-service domain exceptions nearly free
 * in this codebase, and it is worth seeing once. The shared
 * {@code OriahExceptionHandlerSupport} already maps
 * {@code IllegalStateException} to <strong>409 Conflict</strong>. Extending it
 * means this type inherits the correct status with <em>no</em> shared
 * registration and no shared knowledge of inventory's vocabulary — the starter
 * never learns that stock exists.
 *
 * <p>So the rule the refactor settled on: <strong>share the status mappings,
 * not the domain types.</strong> A service's own
 * {@link GlobalExceptionHandler} then only refines the problem {@code type}
 * slug, which is presentation, rather than re-deriving a status.
 *
 * <h2>What it replaced, and why 400 was wrong</h2>
 *
 * <p>All three call sites threw {@link IllegalArgumentException}, which the
 * shared advice maps to <strong>400 Bad Request</strong>. That is the wrong
 * answer and, worse, the wrong <em>advice</em>: 400 tells a client its input
 * was malformed and should be corrected, when the input was perfectly valid and
 * the right response is to retry later or ask for fewer units. A client acting
 * on a 400 would not retry at all.
 *
 * <p>The distinction is preserved carefully rather than applied wholesale.
 * {@code Inventory.requirePositive} still throws
 * {@code IllegalArgumentException} for {@code qty <= 0}, because that genuinely
 * <em>is</em> a malformed argument and 400 is correct for it. Only the three
 * branches whose outcome depends on current state became 409:
 *
 * <ul>
 *   <li>{@code reserve(qty)} where {@code qty > available()}</li>
 *   <li>{@code release(qty)} where {@code qty > reserved}</li>
 *   <li>{@code confirm(qty)} where {@code qty > reserved}</li>
 * </ul>
 *
 * <p>The message is deliberately specific — it names the requested quantity and
 * what is actually available — because these are admin and saga endpoints, not
 * anonymous ones. Unlike the not-found messages in promotion, order and
 * payment, there is no enumeration concern: the caller already holds
 * {@code INVENTORY_ADMIN} or is the checkout orchestrator, and knowing the
 * available count is the whole point of the error.
 */
public class InsufficientStockException extends IllegalStateException {

    public InsufficientStockException(String message) {
        super(message);
    }

    /**
     * {@code cannot <verb> <qty> units; only <actual> <noun>} — the phrasing the
     * three call sites used before, kept verbatim so the only change is the
     * status.
     */
    public static InsufficientStockException cannot(String verb, int qty, int actual, String noun) {
        return new InsufficientStockException(
                "cannot %s %d units; only %d %s".formatted(verb, qty, actual, noun));
    }
}

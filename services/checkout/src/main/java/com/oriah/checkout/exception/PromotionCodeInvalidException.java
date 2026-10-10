package com.oriah.checkout.exception;

/**
 * The promotion code the shopper supplied cannot be used.
 *
 * <p><strong>422 Unprocessable Content</strong>: the request is syntactically
 * valid — there is nothing wrong with its shape — but one value in it is
 * semantically unacceptable. 400 would imply a malformed request, and 404
 * would imply the <em>checkout</em> endpoint does not exist.
 *
 * <p>The detail deliberately says only that the code cannot be used.
 * promotion-service answers 404 for unknown, expired, disabled <em>and</em>
 * exhausted, as one status on purpose, so the response cannot be used to probe
 * which guard failed or to confirm that a guessed code exists. Checkout
 * translates the status but must not undo that: a helpful
 * "this code expired on the 3rd" would hand back exactly the signal the single
 * 404 exists to withhold.
 *
 * <p>Separate from {@link DownstreamUnavailableException} because the two need
 * opposite things from the shopper. 422 means "change the code"; 503 means
 * "keep the code and try again". Reported as the old {@code 200 OK} with
 * {@code "message": "promotion code invalid: SPRING10"}, an outage in
 * promotion-service told every shopper their valid code was bad.
 */
public class PromotionCodeInvalidException extends RuntimeException {

    private final String code;

    public PromotionCodeInvalidException(String code) {
        super("That promotion code cannot be used. Remove it to continue, or "
                + "check the code and try again.");
        this.code = code;
    }

    /** The rejected code. Logged for support; never echoed in the body. */
    public String code() {
        return code;
    }
}

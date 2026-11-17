package com.oriah.payment.provider;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;

/**
 * The company that collects money from a customer's wallet or card for Oriah (ZynlePay, Phase 13), as payment needs it: ask it to collect, and
 * ask it what became of that. Everything ZynlePay-shaped is behind this, in one adapter.
 *
 * <h2>What an implementation promises</h2>
 * <ul>
 *   <li><strong>It never throws for something the provider did or did not do.</strong> Every outcome is one of the answers below.</li>
 *   <li><strong>{@link NotSent} only when no byte can have left</strong> (no connection was made). A timeout after connecting, a broken reply, an
 *       HTTP error, a code nobody listed, a "duplicate reference": all {@link Unclear}, because the request may exist at the provider.</li>
 *   <li><strong>{@link Refused} and {@link Failed} only on a code listed as exactly that.</strong> Nothing else ends an attempt.</li>
 *   <li><strong>{@link Paid} only on the listed "paid" code, with an amount it could read or none at all.</strong></li>
 *   <li>A reference is sent in a "collect" request at most once by the caller; an implementation never repeats a request by itself.</li>
 *   <li>No answer, log line or exception text carries a credential or a whole reply.</li>
 * </ul>
 * There is no refund, void or payout call: Oriah's owner pays refunds by hand (Phase 12).
 */
public interface PaymentProvider {

    Push startMobileMoney(String referenceNo, String msisdnDigits, BigDecimal amount, String currency);

    /** @param returnUrl where the provider's page sends the customer back to when they are done: this order's own payment page at the shop */
    Push startCard(String referenceNo, BigDecimal amount, String currency, String description, CardPayer payer, String returnUrl);

    Status status(String referenceNo);

    /** Who is paying by card, as the provider's hosted page is told. Never a card number: the customer types that on the provider's own page. */
    record CardPayer(String firstName, String lastName, String email, String phone, String address, String city) {
    }

    // ---------------------------------------------------------------- the answers to "collect this"

    sealed interface Push permits Accepted, Redirect, Refused, CredentialsRefused, NotSent, Unclear {
    }

    /** The provider took the request: the customer is being asked to approve. Not money. */
    record Accepted(String code, String transactionId, String operatorReference, String operator) implements Push {
    }

    /** A card payment: the customer must be sent to the provider's page at {@code url}. Not money. */
    record Redirect(String code, URI url, String transactionId) implements Push {
    }

    /** A code listed as a definite refusal: nothing was started. */
    record Refused(String code) implements Push {
    }

    /** The provider refused Oriah itself (credentials, an address not allowed). Whether anything was started is not known. */
    record CredentialsRefused(String code) implements Push {
    }

    /** No connection was made: nothing left this machine. */
    record NotSent(String detail) implements Push {
    }

    /** Anything else. The request may exist at the provider: the attempt stays pending and is asked about. */
    record Unclear(String code, String detail) implements Push {
    }

    // ---------------------------------------------------------------- the answers to "what became of it"

    sealed interface Status permits Paid, Failed, Pending, NotFound, StatusRefused, Unreadable, NoAnswer {
    }

    /** The provider says the money was collected. Amount, currency and time are what it reported, or null where it reported none. */
    record Paid(String code, BigDecimal amount, String currency, Instant paidAt, String transactionId, String operatorReference, String operator)
            implements Status {
    }

    record Failed(String code) implements Status {
    }

    record Pending(String code) implements Status {
    }

    /** A code listed as "no such reference". Treated as pending: it proves nothing about a request that may still be on its way. */
    record NotFound(String code) implements Status {
    }

    record StatusRefused(String code) implements Status {
    }

    /** It answered, and what it said cannot be acted on: an unlisted code, a reply that is not JSON, a "paid" whose amount cannot be read. */
    record Unreadable(String code, String detail) implements Status {
    }

    record NoAnswer(String detail) implements Status {
    }
}

package com.oriah.payment.provider;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.oriah.payment.client.OrderClient;
import com.oriah.payment.confirmation.ProviderConfirmations;
import com.oriah.payment.confirmation.ReferenceNumber;
import com.oriah.payment.dto.StartProviderPaymentRequest;
import com.oriah.payment.events.ProviderPaymentEvents;
import com.oriah.payment.exception.PaymentProblem;
import com.oriah.payment.provider.PaymentProvider.Accepted;
import com.oriah.payment.provider.PaymentProvider.CardPayer;
import com.oriah.payment.provider.PaymentProvider.CredentialsRefused;
import com.oriah.payment.provider.PaymentProvider.NotSent;
import com.oriah.payment.provider.PaymentProvider.Push;
import com.oriah.payment.provider.PaymentProvider.Redirect;
import com.oriah.payment.provider.PaymentProvider.Refused;
import com.oriah.payment.provider.PaymentProvider.Unclear;
import com.oriah.payment.provider.ZynlePayCodes.PushAnswer;
import com.oriah.payment.provider.zynlepay.ZynlePaySetup;

import feign.FeignException;
import lombok.extern.slf4j.Slf4j;

/**
 * A shopper pays an order through ZynlePay (Phase 13): starting an attempt, and reading it back.
 *
 * <h2>The order of work, and why</h2>
 * <ol>
 *   <li><b>The attempt is written</b> (STARTING), under the lock that makes one writer at a time of an order's money. One attempt holds an order
 *       at a time, and none starts while money already stands for it.</li>
 *   <li><b>The order is asked to take it</b>, with the shopper's own token, and answers what is owed. That is the amount: a shopper never sends
 *       one. An order that refuses says why in its own words, and nothing has been asked of ZynlePay.</li>
 *   <li><b>The attempt is committed as PENDING</b>, with the amount and the tariff of that moment, BEFORE anything is sent.</li>
 *   <li><b>ZynlePay is asked, once.</b> Never again under that reference, by this code or by a retry: a second request would be a second request
 *       for money.</li>
 *   <li><b>Its answer is recorded.</b> Only a definite refusal, or the request never leaving, ends the attempt. Everything else leaves it pending,
 *       to be asked about: nothing ambiguous is a failure.</li>
 * </ol>
 * A crash anywhere leaves either an attempt that never left (it is failed as never sent after a short grace) or a pending attempt that is asked
 * about. The same Idempotency-Key and request is the same attempt, wherever it had got to.
 *
 * <p>No transaction is open during either call out: no lock and no database connection is held while order or ZynlePay answers.
 */
@Service
@Slf4j
public class ProviderPaymentService {

    static final String ACTOR = "system:zynlepay";
    private static final Duration WINDOW = Duration.ofHours(1);
    private static final Pattern PROBLEM_TYPE = Pattern.compile("\"type\"\\s*:\\s*\"[^\"]*/([a-z0-9-]+)\"");
    private static final Pattern PROBLEM_DETAIL = Pattern.compile("\"detail\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    /** The attempt, and whether this call made it (false: a repeat of an earlier call with the same Idempotency-Key). */
    public record Started(ProviderPaymentView view, boolean created) {
    }

    /** An attempt as the database holds it. Never leaves this package: it carries the whole number and the reference. */
    record Attempt(UUID id, String referenceNo, UUID orderId, String keycloakId, String channel, String method, String payerMsisdn, BigDecimal amount,
                   String currency, String status, String failureCode, String unresolvedReason, boolean holdsOrder, String providerCode, String redirectUrl,
                   Instant startedAt, Instant sentAt, Instant verifiedAt, UUID confirmationId) {

        boolean card() {
            return "CARD".equals(channel);
        }
    }

    static final String SELECT = """
            SELECT id, reference_no, order_id, keycloak_id, channel, method, payer_msisdn, amount, currency, status, failure_code, unresolved_reason,
                   holds_order, provider_code, redirect_url, started_at, sent_at, verified_at, confirmation_id
              FROM provider_payment""";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final OrderClient orders;
    private final PaymentProvider provider;
    private final ZynlePaySetup setup;
    private final ProviderPaymentEvents events;
    private final Clock clock;

    public ProviderPaymentService(JdbcTemplate jdbc, TransactionTemplate tx, OrderClient orders, PaymentProvider provider, ZynlePaySetup setup,
                                  ProviderPaymentEvents events, Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.orders = orders;
        this.provider = provider;
        this.setup = setup;
        this.events = events;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- start

    /**
     * @param payer who is paying, from the shopper's own account (a card page is told the name and the email)
     * @throws IllegalArgumentException 400 for a key or a channel that is not one
     * @throws PaymentProblem           400 {@code amount-is-not-typed}, {@code card-details-not-accepted}, {@code phone-required},
     *                                  {@code phone-not-for-card}, {@code invalid-phone}, {@code not-a-mobile-money-number}; 404 {@code order-not-found};
     *                                  409 {@code idempotency-key-reused}, {@code channel-not-offered}, {@code money-already-recorded},
     *                                  {@code provider-payment-in-progress}, and whatever the order refuses with, under its own name; 429
     *                                  {@code too-many-payment-attempts}; 503 {@code order-unavailable}, {@code payments-unavailable}
     */
    public Started start(String shopper, CardPayer payer, StartProviderPaymentRequest request, String idempotencyKey) {
        String key = idempotencyKey == null ? "" : idempotencyKey.strip();
        if (key.isEmpty() || key.length() > 128) {
            throw new IllegalArgumentException("Idempotency-Key must be 1 to 128 characters");
        }
        // Refused by name before anything else: an amount or a card detail must never look as if it had been taken.
        if (request.typesAnAmount()) {
            throw new PaymentProblem(HttpStatus.BAD_REQUEST, "amount-is-not-typed",
                    "the amount is the order's own total and is read from the order: send no amount, total, currency or fee");
        }
        if (request.carriesCardDetails()) {
            throw new PaymentProblem(HttpStatus.BAD_REQUEST, "card-details-not-accepted",
                    "Oriah takes no card details: the card is typed on the payment provider's own page, which this request answers with");
        }
        String channel = request.channel().strip().toUpperCase(Locale.ROOT);
        if (!channel.equals("MOBILE_MONEY") && !channel.equals("CARD")) {
            throw new IllegalArgumentException("channel must be MOBILE_MONEY or CARD, was '" + request.channel() + "'");
        }
        String msisdn = null;
        String method = "CARD";
        if (channel.equals("CARD")) {
            if (request.phone() != null && !request.phone().isBlank()) {
                throw new PaymentProblem(HttpStatus.BAD_REQUEST, "phone-not-for-card", "a card payment takes no phone number");
            }
        } else {
            if (request.phone() == null || request.phone().isBlank()) {
                throw new PaymentProblem(HttpStatus.BAD_REQUEST, "phone-required", "give the mobile-money number the payment is to be approved on");
            }
            msisdn = msisdn(request.phone());
            method = setup.wallets().of(msisdn).orElseThrow(() -> new PaymentProblem(HttpStatus.BAD_REQUEST, "not-a-mobile-money-number",
                    "that is not an MTN, Airtel or Zamtel mobile-money number"));
        }
        String fingerprint = fingerprint(request.orderId(), channel, msisdn);

        Optional<Attempt> repeat = repeat(shopper, key, fingerprint);
        if (repeat.isPresent()) {
            Attempt earlier = repeat.get();
            return new Started("STARTING".equals(earlier.status()) ? carryOn(earlier.id(), payer) : view(earlier), false);
        }
        if (!setup.offers(channel)) {
            throw new PaymentProblem(HttpStatus.CONFLICT, "channel-not-offered",
                    (channel.equals("CARD") ? "card payments are" : "mobile-money payments are") + " not offered at the moment");
        }
        refuseTooMany("keycloak_id", shopper, setup.settings().limits().startsPerShopperPerHour(), "you have started too many payments in the last hour");
        if (msisdn != null) {
            refuseTooMany("payer_msisdn", msisdn, setup.settings().limits().startsPerNumberPerHour(),
                    "too many payments have been asked of that number in the last hour");
        }

        final String wallet = method;
        final String number = msisdn;
        UUID id = tx.execute(status -> begin(shopper, request.orderId(), channel, wallet, number, key, fingerprint));
        return new Started(carryOn(id, payer), true);
    }

    /** Transaction 1: the attempt exists, STARTING, holding its order; or the start is refused and nothing is written. */
    private UUID begin(String shopper, UUID orderId, String channel, String method, String msisdn, String key, String fingerprint) {
        // Nothing about the order is read or said here: whose order it is is not known until the order itself answers (carryOn). An attempt that
        // is only STARTING stands in nobody's way; what must not happen twice is checked when it becomes PENDING, under this same lock.
        ProviderConfirmations.lockOrder(jdbc, orderId);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        for (int tries = 0; tries < 3; tries++) {
            UUID id = UUID.randomUUID();
            List<UUID> inserted = jdbc.query("""
                    INSERT INTO provider_payment (id, provider, reference_no, order_id, keycloak_id, channel, method, payer_msisdn, status, idempotency_key,
                                                  request_fingerprint, started_at)
                    VALUES (?, 'ZYNLEPAY', ?, ?, ?, ?, ?, ?, 'STARTING', ?, ?, ?)
                    ON CONFLICT DO NOTHING
                    RETURNING id""", (rs, n) -> rs.getObject("id", UUID.class), id, ProviderReference.mint(), orderId, shopper, channel, method, msisdn, key,
                    fingerprint, Timestamp.from(now));
            if (!inserted.isEmpty()) {
                return id;
            }
            // Something stood in the way: this very request sent twice at once, another attempt holding the order, or (hardly ever) a reference taken.
            Optional<Attempt> sentTwice = repeat(shopper, key, fingerprint);
            if (sentTwice.isPresent()) {
                return sentTwice.get().id();
            }
            jdbc.query(SELECT + " WHERE order_id = ? AND holds_order", (rs, row) -> {
                PaymentProblem problem = new PaymentProblem(HttpStatus.CONFLICT, "provider-payment-in-progress",
                        "a payment through ZynlePay is already in progress for this order: wait for it to finish before starting another");
                // Whose attempt it is is told only to its own shopper.
                throw shopper.equals(rs.getString("keycloak_id")) ? problem.with("providerPaymentId", rs.getObject("id", UUID.class)) : problem;
            }, orderId);
        }
        throw new IllegalStateException("a payment attempt for order " + orderId + " could not be written");
    }

    /**
     * From an attempt that is STARTING to its answer: ask the order, commit PENDING, ask ZynlePay, record what it said. Run by the start that made
     * the attempt, and by a repeat of it that finds it still STARTING (the order could not be asked the first time).
     */
    private ProviderPaymentView carryOn(UUID id, CardPayer payer) {
        Attempt attempt = find(id).orElseThrow();
        // The newest attempt for the order that is over. Sent or not: one that never left holds no money, and the order may have taken it all the
        // same (its answer lost on the way back), with nothing left to tell it to let go.
        UUID supersedes = jdbc.query("SELECT id FROM provider_payment WHERE order_id = ? AND NOT holds_order AND id <> ? "
                + "ORDER BY started_at DESC, id LIMIT 1", (rs, n) -> rs.getObject("id", UUID.class), attempt.orderId(), id).stream().findFirst().orElse(null);
        OrderClient.Taken taken;
        try {
            taken = orders.takeProviderPayment(attempt.orderId(), new OrderClient.Take(id, supersedes));
        } catch (FeignException refused) {
            throw orderSaidNo(id, refused);
        }
        if (taken == null || taken.total() == null || taken.total().signum() <= 0 || !"ZMW".equals(taken.currency())) {
            tx.executeWithoutResult(status -> fail(id, "STARTING", AttemptState.FailureCode.ORDER_REFUSED, null));
            throw new PaymentProblem(HttpStatus.CONFLICT, "order-not-payable", "this order has nothing that can be paid through ZynlePay");
        }
        FeeTariff tariff = setup.tariff(attempt.method());
        if (!tariff.fits(taken.total())) {
            // The fee would take all of it: its money could never be recorded (a fee is never more than the amount). Stopped before anything is asked.
            tx.executeWithoutResult(status -> fail(id, "STARTING", AttemptState.FailureCode.NOT_SENT, null));
            throw new PaymentProblem(HttpStatus.CONFLICT, "amount-too-small-for-channel",
                    "this order's total is too small to be paid " + (attempt.card() ? "by card" : "by mobile money") + ": please pay it another way");
        }

        // Transaction 2: PENDING, with what is asked for, BEFORE it is asked. Only the caller that makes this move goes on to ask.
        Boolean mine = tx.execute(status -> {
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            // Under the lock every writer of this order's money takes: money a person confirmed since this attempt was written is seen here, and
            // this attempt, seen by them, stands in their way from the moment it is PENDING.
            ProviderConfirmations.lockOrder(jdbc, attempt.orderId());
            boolean moneyStands = !jdbc.query("""
                    SELECT c.id FROM payment_confirmation c LEFT JOIN payment_confirmation_void v ON v.confirmation_id = c.id
                     WHERE c.order_id = ? AND v.id IS NULL AND c.outcome IN ('PENDING', 'SETTLED') LIMIT 1""", (rs, n) -> rs.getObject(1), attempt.orderId())
                    .isEmpty();
            if (moneyStands) {
                fail(id, "STARTING", AttemptState.FailureCode.NOT_SENT, null);
                return null;
            }
            int moved = jdbc.update("""
                    UPDATE provider_payment
                       SET status = 'PENDING', amount = ?, currency = ?, fee_percent = ?, fee_fixed = ?, sent_at = ?, next_check_at = ?, version = version + 1
                     WHERE id = ? AND status = 'STARTING'""", taken.total(), taken.currency(), tariff.percent(), tariff.fixed(), Timestamp.from(now),
                    Timestamp.from(setup.schedule().first(now)), id);
            if (moved == 0) {
                Attempt now2 = lock(id);
                if ("FAILED".equals(now2.status())) {
                    // It was given up as never sent while the order was being asked; the order has just taken it, so it is told again to let go.
                    announce(now2, clock.instant().truncatedTo(ChronoUnit.MICROS), ACTOR);
                }
            }
            return moved == 1;
        });
        if (mine == null) {
            throw new PaymentProblem(HttpStatus.CONFLICT, "money-already-recorded",
                    "money is already recorded for this order: it is paid, or is being paid. Look at the order again in a moment");
        }
        if (!mine) {
            return view(find(id).orElseThrow());
        }

        Instant askedAt = clock.instant();
        Push push = attempt.card()
                ? provider.startCard(attempt.referenceNo(), taken.total(), taken.currency(), "Oriah order " + attempt.orderId().toString().substring(0, 8)
                .toUpperCase(Locale.ROOT), payer, setup.returnUrl().replace("{orderId}", attempt.orderId().toString()).replace("{id}", id.toString()))
                : provider.startMobileMoney(attempt.referenceNo(), Wallets.digits(attempt.payerMsisdn()), taken.total(), taken.currency());

        // Transaction 3: what ZynlePay said.
        tx.executeWithoutResult(status -> record(id, push, askedAt));
        if (push instanceof CredentialsRefused) {
            throw new PaymentProblem(HttpStatus.SERVICE_UNAVAILABLE, "payments-unavailable",
                    "payments through ZynlePay cannot be taken at the moment. Nothing has been taken from you: please try again later")
                    .with("retryAfterSeconds", 60);
        }
        return view(find(id).orElseThrow());
    }

    private void record(UUID id, Push push, Instant askedAt) {
        Attempt attempt = lock(id);
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        String outcome;
        String code;
        switch (push) {
            case Accepted accepted -> {
                outcome = "ACCEPTED";
                code = accepted.code();
                jdbc.update("""
                        UPDATE provider_payment SET provider_code = ?, provider_transaction_id = ?, provider_transaction_key = ?, operator_reference = ?,
                               operator_reference_key = ?, operator = ?, version = version + 1 WHERE id = ?""", code, cut(accepted.transactionId(), 128),
                        referenceKey(accepted.transactionId()), cut(accepted.operatorReference(), 128), referenceKey(accepted.operatorReference()),
                        cut(accepted.operator(), 16), id);
            }
            case Redirect redirect -> {
                outcome = "REDIRECT";
                code = redirect.code();
                jdbc.update("UPDATE provider_payment SET provider_code = ?, redirect_url = ?, provider_transaction_id = ?, provider_transaction_key = ?, "
                        + "version = version + 1 WHERE id = ?", code, redirect.url().toString(), cut(redirect.transactionId(), 128),
                        referenceKey(redirect.transactionId()), id);
            }
            case Refused refused -> {
                outcome = "REFUSED";
                code = refused.code();
                if ("PENDING".equals(attempt.status())) {
                    fail(id, "PENDING", AttemptState.FailureCode.PROVIDER_REFUSED, code);
                }
            }
            case NotSent notSent -> {
                outcome = "NOT_SENT";
                code = null;
                if ("PENDING".equals(attempt.status())) {
                    fail(id, "PENDING", AttemptState.FailureCode.NOT_SENT, null);
                }
            }
            case CredentialsRefused turnedAway -> {
                outcome = "CREDENTIALS";
                code = turnedAway.code();
                jdbc.update("UPDATE provider_payment SET provider_code = ?, version = version + 1 WHERE id = ?", code, id);
            }
            case Unclear unclear -> {
                // The request may be at ZynlePay: the attempt stays pending and is asked about.
                outcome = "UNCLEAR";
                code = unclear.code();
                jdbc.update("UPDATE provider_payment SET provider_code = ?, version = version + 1 WHERE id = ?", cut(code, 8), id);
            }
        }
        jdbc.update("INSERT INTO provider_payment_check (payment_id, source, asked_by, asked_at, answered_at, outcome, provider_code) "
                + "VALUES (?, 'START', ?, ?, ?, ?, ?)", id, "user:" + attempt.keycloakId(), Timestamp.from(askedAt), Timestamp.from(now), outcome, cut(code, 8));
    }

    /**
     * Ends an attempt that is in {@code from} as FAILED, and announces it (order lets go of the attempt). A sent attempt that fails for a reason
     * ZynlePay gave is asked about once more later, to catch ZynlePay contradicting itself; one that never left is not.
     */
    private void fail(UUID id, String from, AttemptState.FailureCode failure, String providerCode) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        boolean reachedZynlePay = failure != AttemptState.FailureCode.ORDER_REFUSED && failure != AttemptState.FailureCode.NOT_SENT;
        int moved = jdbc.update("""
                UPDATE provider_payment
                   SET status = 'FAILED', failure_code = ?, holds_order = false, failed_at = ?, next_check_at = NULL, check_lease_until = NULL,
                       final_recheck_at = ?, provider_code = COALESCE(?, provider_code), version = version + 1
                 WHERE id = ? AND status = ?""", failure.name(), Timestamp.from(now),
                reachedZynlePay ? Timestamp.from(setup.schedule().finalRecheck(now)) : null, cut(providerCode, 8), id, from);
        if (moved == 1) {
            announce(lock(id), now, ACTOR);
            log.info("Provider payment {} FAILED: {}", id, failure);
        }
    }

    private void announce(Attempt attempt, Instant at, String actor) {
        events.updated(attempt.id(), attempt.orderId(), attempt.keycloakId(), attempt.channel(), attempt.method(), attempt.status(), attempt.failureCode(),
                attempt.unresolvedReason(), attempt.amount(), attempt.currency(), attempt.confirmationId(), attempt.startedAt(), at, actor);
    }

    /** What the order's refusal means for the attempt and for the shopper. */
    private PaymentProblem orderSaidNo(UUID id, FeignException refused) {
        int status = refused.status();
        if (status < 400 || status >= 500) {
            // No answer, or the order's own failure: nothing was decided. The attempt stays as it is, and the same key carries on with it.
            log.warn("Order could not be asked to take provider payment {} (status {})", id, status);
            return new PaymentProblem(HttpStatus.SERVICE_UNAVAILABLE, "order-unavailable",
                    "the order could not be reached just now. Nothing has been asked of your phone or card: try again in a moment with the same request")
                    .with("retryAfterSeconds", 5);
        }
        tx.executeWithoutResult(s -> fail(id, "STARTING", AttemptState.FailureCode.ORDER_REFUSED, null));
        if (status == 404) {
            return new PaymentProblem(HttpStatus.NOT_FOUND, "order-not-found", "there is no such order of yours");
        }
        String body = refused.contentUTF8();
        Matcher type = PROBLEM_TYPE.matcher(body == null ? "" : body);
        Matcher detail = PROBLEM_DETAIL.matcher(body == null ? "" : body);
        HttpStatus answered = HttpStatus.resolve(status) == null ? HttpStatus.CONFLICT : HttpStatus.resolve(status);
        // The order's own name for its refusal and its own words: the shopper can act on them. One it gave no name to is said plainly.
        return new PaymentProblem(status == 401 || status == 403 ? HttpStatus.CONFLICT : answered, type.find() ? type.group(1) : "order-refused",
                detail.find() ? detail.group(1).replace("\\\"", "\"") : "this order cannot be paid at the moment");
    }

    // ---------------------------------------------------------------- reads

    /** @throws PaymentProblem 404 {@code provider-payment-not-found}: no such attempt, or it is somebody else's */
    public ProviderPaymentView get(String shopper, UUID id) {
        return find(id).filter(attempt -> attempt.keycloakId().equals(shopper)).map(this::view)
                .orElseThrow(() -> new PaymentProblem(HttpStatus.NOT_FOUND, "provider-payment-not-found", "there is no such payment of yours"));
    }

    /** The shopper's own attempts for an order, newest first. */
    public List<ProviderPaymentView> mine(String shopper, UUID orderId) {
        return jdbc.query(SELECT + " WHERE order_id = ? AND keycloak_id = ? ORDER BY started_at DESC, id LIMIT 20", this::attempt, orderId, shopper)
                .stream().map(this::view).toList();
    }

    ProviderPaymentView view(Attempt a) {
        String nextStep = switch (a.status()) {
            case "VERIFIED" -> "DONE";
            case "FAILED" -> "TRY_AGAIN";
            case "PENDING" -> a.card() ? (a.redirectUrl() != null ? "OPEN_CARD_PAGE" : "WAIT")
                    : (setup.codes().push(a.providerCode()) == PushAnswer.INITIATED ? "APPROVE_ON_PHONE" : "WAIT");
            default -> "WAIT";
        };
        Integer checkAgain = switch (a.status()) {
            case "STARTING", "PENDING" -> (int) setup.schedule().fastEvery().toSeconds();
            case "UNRESOLVED" -> (int) setup.schedule().slowEvery().toSeconds();
            default -> null;
        };
        return new ProviderPaymentView(a.id(), a.orderId(), a.channel(), a.method(), a.status(), a.failureCode(), a.amount(), a.currency(),
                Wallets.masked(a.payerMsisdn()), "PENDING".equals(a.status()) ? a.redirectUrl() : null, nextStep, checkAgain, a.startedAt(), a.verifiedAt(),
                a.confirmationId());
    }

    // ---------------------------------------------------------------- plumbing

    Optional<Attempt> find(UUID id) {
        return jdbc.query(SELECT + " WHERE id = ?", this::attempt, id).stream().findFirst();
    }

    Attempt lock(UUID id) {
        return jdbc.query(SELECT + " WHERE id = ? FOR UPDATE", this::attempt, id).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("provider payment " + id + " vanished"));
    }

    /** The attempt this shopper's key already made, if the request is the same; a request that is not is refused. */
    private Optional<Attempt> repeat(String shopper, String key, String fingerprint) {
        record Prior(UUID id, String fingerprint) {
        }
        Optional<Prior> prior = jdbc.query("SELECT id, request_fingerprint FROM provider_payment WHERE keycloak_id = ? AND idempotency_key = ?",
                (rs, n) -> new Prior(rs.getObject("id", UUID.class), rs.getString("request_fingerprint")), shopper, key).stream().findFirst();
        if (prior.isEmpty()) {
            return Optional.empty();
        }
        if (!prior.get().fingerprint().equals(fingerprint)) {
            throw new PaymentProblem(HttpStatus.CONFLICT, "idempotency-key-reused",
                    "this Idempotency-Key was used for a different payment: send a new key for a new payment").with("providerPaymentId", prior.get().id());
        }
        return find(prior.get().id());
    }

    private void refuseTooMany(String column, String value, int most, String why) {
        Instant now = clock.instant();
        List<Timestamp> recent = jdbc.query("SELECT started_at FROM provider_payment WHERE " + column + " = ? AND started_at > ? ORDER BY started_at",
                (rs, n) -> rs.getTimestamp(1), value, Timestamp.from(now.minus(WINDOW)));
        if (recent.size() >= most) {
            long wait = Math.max(1, Duration.between(now, recent.getFirst().toInstant().plus(WINDOW)).toSeconds());
            throw new PaymentProblem(HttpStatus.TOO_MANY_REQUESTS, "too-many-payment-attempts", why + ": try again later").with("retryAfterSeconds", wait);
        }
    }

    /** A Zambian mobile number in the one form numbers are kept in: +260 and nine digits. */
    private static String msisdn(String typed) {
        String digits = typed.replaceAll("[\\s().-]", "");
        if (digits.startsWith("+")) {
            digits = digits.substring(1);
        } else if (digits.startsWith("00")) {
            digits = digits.substring(2);
        }
        if (digits.matches("0[0-9]{9}")) {
            digits = "260" + digits.substring(1);
        }
        if (!digits.matches("260[0-9]{9}")) {
            throw new PaymentProblem(HttpStatus.BAD_REQUEST, "invalid-phone", "give the number as 09XXXXXXXX or +2609XXXXXXXX");
        }
        return "+" + digits;
    }

    /** The one spelling a staff reference is compared in, or null when ZynlePay's id has none (it is then never matched against one). */
    static String referenceKey(String theirs) {
        if (theirs == null) {
            return null;
        }
        try {
            return ReferenceNumber.normalise(theirs);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String cut(String value, int most) {
        return value == null || value.length() <= most ? value : value.substring(0, most);
    }

    private static String fingerprint(UUID orderId, String channel, String msisdn) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((orderId + "|" + channel + "|" + (msisdn == null ? "" : msisdn)).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private Attempt attempt(ResultSet rs, int row) throws SQLException {
        Timestamp sentAt = rs.getTimestamp("sent_at");
        Timestamp verifiedAt = rs.getTimestamp("verified_at");
        return new Attempt(rs.getObject("id", UUID.class), rs.getString("reference_no"), rs.getObject("order_id", UUID.class), rs.getString("keycloak_id"),
                rs.getString("channel"), rs.getString("method"), rs.getString("payer_msisdn"), rs.getBigDecimal("amount"), rs.getString("currency"),
                rs.getString("status"), rs.getString("failure_code"), rs.getString("unresolved_reason"), rs.getBoolean("holds_order"),
                rs.getString("provider_code"), rs.getString("redirect_url"), rs.getTimestamp("started_at").toInstant(),
                sentAt == null ? null : sentAt.toInstant(), verifiedAt == null ? null : verifiedAt.toInstant(), rs.getObject("confirmation_id", UUID.class));
    }
}

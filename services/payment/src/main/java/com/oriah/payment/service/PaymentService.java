package com.oriah.payment.service;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.payment.dto.CreateIntentRequest;
import com.oriah.payment.gateway.PaymentGateway;
import com.oriah.payment.model.PaymentIntent;
import com.oriah.payment.model.PaymentStatus;
import com.oriah.payment.repository.PaymentIntentRepository;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class PaymentService {

    private final PaymentIntentRepository repository;
    private final PaymentGateway gateway;

    public PaymentService(PaymentIntentRepository repository, PaymentGateway gateway) {
        this.repository = repository;
        this.gateway = gateway;
    }

    @Transactional
    public PaymentIntent createIntent(String keycloakId, CreateIntentRequest req) {
        // No default. CreateIntentRequest requires a currency, so there is
        // nothing to substitute -- and the substitute was "USD" on a platform
        // priced in Kwacha, written here AND in the PaymentIntent constructor,
        // which is how two defaults drift apart.
        PaymentIntent intent = new PaymentIntent(
                req.orderId(),
                keycloakId,
                req.amount(),
                req.currency()
        );
        PaymentIntent saved = repository.save(intent);
        log.info("Created PaymentIntent id={} orderId={} amount={}", saved.id(), saved.orderId(), saved.amount());
        return saved;
    }

    @Transactional(readOnly = true)
    public Optional<PaymentIntent> findById(UUID id) {
        return repository.findById(id);
    }

    /**
     * Charge the gateway with the supplied test card and transition the
     * intent to SUCCEEDED or FAILED. Returns the post-transition intent.
     * If the intent doesn't exist, returns empty. If it's already in a
     * terminal state, throws — re-confirming a SUCCEEDED/FAILED intent
     * is a programming error.
     */
    @Transactional
    /**
     * Charges the gateway and settles the intent.
     *
     * <h2>The owner is a parameter, and that is the fix</h2>
     *
     * This method used to take only the id, so it found the intent, called the
     * gateway and marked the status with no idea who was asking. The caller
     * checked ownership on the RESULT:
     *
     * <pre>
     *   service.confirm(id, card)                                     // charges
     *          .filter(i -&gt; i.keycloakId().equals(jwt.getSubject()))  // checks
     *          .orElseThrow(() -&gt; unknownIntent(id));
     * </pre>
     *
     * which is a check performed after the money has already moved. One
     * customer confirming another's intent settled it for real and received a
     * 404: a charge that looks to the caller like nothing happened, and to the
     * victim like they paid. {@code ConfirmAuthorizationTest} pins that the
     * victim's intent is still PENDING afterwards, because the status -- not
     * the response code -- is what was actually broken.
     *
     * <p>The filter lives here rather than in the controller deliberately. The
     * gateway call is the irreversible step, so the authorization decision has
     * to sit upstream of it inside the same method, where no future caller can
     * reorder the two.
     *
     * @return empty when no such intent exists OR it belongs to someone else.
     *         The two are deliberately indistinguishable, so a 404 cannot
     *         confirm that another customer's intent id is real.
     */
    public Optional<PaymentIntent> confirm(UUID intentId, String keycloakId, String cardNumber) {
        return repository.findById(intentId)
                .filter(intent -> intent.keycloakId().equals(keycloakId))
                .map(intent -> {
            // Terminal check BEFORE the charge. The guard already existed --
            // markSucceeded() and markFailed() both call requirePending() --
            // but it ran AFTER gateway.charge() below, so confirming a settled
            // intent charged the card, threw, and rolled back to a 409: a
            // second charge with no record of it, because the row that would
            // have recorded it was rolled back with everything else. Two plain
            // confirm calls reached it, since Idempotency-Key is optional.
            //
            // Same shape as the ownership bug above, and the same fix: decide
            // first, charge second.
            //
            // The message is requirePending()'s verbatim, so the 409 body does
            // not move. Deliberately still a 409 rather than an idempotent
            // 200: a shopper retrying WITHOUT an Idempotency-Key must not be
            // told "fine" about a charge this request did not make. The header
            // is the mechanism for a safe retry.
            if (intent.status() != PaymentStatus.PENDING) {
                throw new IllegalStateException(
                        "PaymentIntent " + intent.id() + " already in terminal state " + intent.status());
            }
            var result = gateway.charge(cardNumber, intent.amount(), intent.currency());
            switch (result) {
                case PaymentGateway.Success s -> {
                    intent.markSucceeded();
                    log.info("PaymentIntent id={} SUCCEEDED gatewayRef={}", intent.id(), s.gatewayReference());
                }
                case PaymentGateway.Failure f -> {
                    intent.markFailed(f.reason());
                    log.warn("PaymentIntent id={} FAILED reason={}", intent.id(), f.reason());
                }
            }
            return intent;
        });
    }
}

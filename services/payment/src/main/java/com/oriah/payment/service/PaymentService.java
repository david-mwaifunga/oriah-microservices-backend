package com.oriah.payment.service;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.payment.dto.CreateIntentRequest;
import com.oriah.payment.gateway.PaymentGateway;
import com.oriah.payment.model.PaymentIntent;
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
        PaymentIntent intent = new PaymentIntent(
                req.orderId(),
                keycloakId,
                req.amount(),
                req.currency() == null ? "USD" : req.currency()
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
    public Optional<PaymentIntent> confirm(UUID intentId, String cardNumber) {
        return repository.findById(intentId).map(intent -> {
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

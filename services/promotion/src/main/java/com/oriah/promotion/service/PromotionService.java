package com.oriah.promotion.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.promotion.dto.ApplyResponse;
import com.oriah.promotion.dto.CreatePromotionRequest;
import com.oriah.promotion.model.Promotion;
import com.oriah.promotion.repository.PromotionRepository;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class PromotionService {

    private final PromotionRepository repository;

    public PromotionService(PromotionRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public Promotion create(CreatePromotionRequest req) {
        Promotion p = new Promotion(req.code(), req.type(), req.amount(), req.description(),
                req.validUntil(), req.maxRedemptions() == null ? 0 : req.maxRedemptions());
        Promotion saved = repository.save(p);
        log.info("Created promotion code={} type={} amount={}", saved.code(), saved.type(), saved.amount());
        return saved;
    }

    @Transactional(readOnly = true)
    public Optional<Promotion> findByCode(String code) {
        log.debug("findByCode code={}", code);
        return repository.findByCode(code.toUpperCase());
    }

    @Transactional(readOnly = true)
    public Page<Promotion> findAll(Pageable pageable) {
        return repository.findAll(pageable);
    }

    /**
     * Validate the code against current time + redemption cap and
     * compute the discount on the given subtotal. Returns empty when
     * the code is unknown, expired, disabled, or over-redeemed.
     * Does NOT mutate currentRedemptions (call {@link #redeem} for that).
     */
    @Transactional(readOnly = true)
    public Optional<ApplyResponse> applyToSubtotal(String code, BigDecimal subtotal) {
        return repository.findByCode(code.toUpperCase())
                .filter(p -> p.isRedeemable(Instant.now()))
                .map(p -> {
                    BigDecimal discount = p.computeDiscount(subtotal);
                    return new ApplyResponse(
                            p.code(), subtotal, discount, subtotal.subtract(discount)
                    );
                });
    }

    @Transactional
    public Optional<Promotion> redeem(String code) {
        return repository.findByCode(code.toUpperCase()).map(p -> {
            if (!p.isRedeemable(Instant.now())) return null;
            p.redeem();
            log.info("Redeemed promotion code={} now={}", p.code(), p.currentRedemptions());
            return p;
        }).filter(p -> p != null);
    }

    @Transactional
    public Optional<Promotion> disable(String code) {
        return repository.findByCode(code.toUpperCase()).map(p -> {
            p.disable();
            log.info("Disabled promotion code={}", p.code());
            return p;
        });
    }
}

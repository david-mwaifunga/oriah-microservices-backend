package com.oriah.inventory.service;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.inventory.model.Inventory;
import com.oriah.inventory.repository.InventoryRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Transactional operations on the {@link Inventory} aggregate.
 *
 * <p>The reserve / release / confirm / restock methods share the same
 * pattern: load by id, apply the domain method (which enforces the
 * invariants), let JPA dirty-checking persist on transaction commit.
 * The shared bit is factored into {@link #applyMutation}.
 *
 * <p>Logging follows the convention from catalog: INFO on state
 * transitions, DEBUG on reads and skipped-because-not-found cases.
 */
@Service
@Slf4j
public class InventoryService {

    private final InventoryRepository repository;

    public InventoryService(InventoryRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Optional<Inventory> findByVariantId(UUID variantId) {
        log.debug("findByVariantId variantId={}", variantId);
        return repository.findById(variantId);
    }

    /**
     * Seeds an inventory row for a variant. Idempotent: if a row already
     * exists for the variant, returns the existing one (no overwrite).
     */
    @Transactional
    public Inventory create(UUID variantId, int onHand) {
        return repository.findById(variantId).orElseGet(() -> {
            Inventory created = new Inventory(variantId, onHand);
            Inventory saved = repository.save(created);
            log.info("Created inventory variantId={} onHand={}", saved.variantId(), saved.onHand());
            return saved;
        });
    }

    @Transactional
    public Optional<Inventory> reserve(UUID variantId, int qty) {
        return applyMutation(variantId, inv -> inv.reserve(qty), "Reserved", qty);
    }

    @Transactional
    public Optional<Inventory> release(UUID variantId, int qty) {
        return applyMutation(variantId, inv -> inv.release(qty), "Released", qty);
    }

    @Transactional
    public Optional<Inventory> confirm(UUID variantId, int qty) {
        return applyMutation(variantId, inv -> inv.confirm(qty), "Confirmed", qty);
    }

    @Transactional
    public Optional<Inventory> restock(UUID variantId, int qty) {
        return applyMutation(variantId, inv -> inv.restock(qty), "Restocked", qty);
    }

    /**
     * Common shape of every state-changing operation: find -> apply ->
     * dirty-check persists -> log. Returning {@code Optional.empty()}
     * for unknown variantId lets the controller return 404 cleanly.
     */
    private Optional<Inventory> applyMutation(
            UUID variantId,
            Consumer<Inventory> mutation,
            String pastTense,
            int qty) {
        Optional<Inventory> result = repository.findById(variantId).map(inventory -> {
            mutation.accept(inventory);
            log.info("{} qty={} variantId={} onHand={} reserved={}",
                    pastTense, qty, inventory.variantId(), inventory.onHand(), inventory.reserved());
            return inventory;
        });

        if (result.isEmpty()) {
            log.debug("{} skipped, variantId not found: {}", pastTense, variantId);
        }
        return result;
    }
}

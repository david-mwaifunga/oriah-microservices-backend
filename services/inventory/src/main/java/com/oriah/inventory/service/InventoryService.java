package com.oriah.inventory.service;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.inventory.dto.AdminInventoryRow;
import com.oriah.inventory.model.Inventory;
import com.oriah.inventory.repository.InventoryRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Transactional operations on the {@link Inventory} aggregate.
 *
 * <p>There is no way to create stock, add to it, or sell it here: stock comes only from a received consignment, which
 * brings its cost lots ({@link StockService#applyReceipt}), and a sale drains those lots ({@link StockService#consume}).
 *
 * <p>Holds are not here: an order's hold is a reservation ({@link ReservationService}) with an owner and a clock.
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
     * The admin list. Read-only and projection-based: the query builds
     * AdminInventoryRow directly, so no Inventory entities are loaded to be
     * mapped and discarded.
     */
    @Transactional(readOnly = true)
    public Page<AdminInventoryRow> findAdminRows(Boolean outOfStock, Pageable pageable) {
        return repository.findAdminRows(outOfStock, pageable);
    }
}

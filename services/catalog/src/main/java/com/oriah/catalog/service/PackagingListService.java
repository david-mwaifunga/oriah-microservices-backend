package com.oriah.catalog.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.catalog.repository.ProductVariantRepository;

/**
 * The boxes a salesperson can choose from when a sale is recorded (Phase 9): every active variant of a packaging product that is not deleted, by name and SKU.
 * Identity only, never a price and never a cost (Q29).
 */
@Service
public class PackagingListService {

    /** One box: its variant, its product and how to read them. No price and no cost. */
    public record BoxEntry(UUID variantId, UUID productId, String title, String variantTitle, String sku) {
    }

    private final ProductVariantRepository variants;

    public PackagingListService(ProductVariantRepository variants) {
        this.variants = variants;
    }

    @Transactional(readOnly = true)
    public List<BoxEntry> boxes() {
        return variants.findPackagingInUse().stream()
                .map(v -> new BoxEntry(v.id(), v.product().id(), v.product().title(), DefaultVariantPricingService.variantTitle(v.product(), v), v.sku()))
                .toList();
    }
}

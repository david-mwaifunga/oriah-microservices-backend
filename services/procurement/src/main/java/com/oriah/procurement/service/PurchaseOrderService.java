package com.oriah.procurement.service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.observability.OriahExceptionHandlerSupport.FieldErrorView;
import com.oriah.observability.ResourceNotFoundException;
import com.oriah.procurement.client.CatalogClient.VariantLookupResult.Entry;
import com.oriah.procurement.client.CatalogVariants;
import com.oriah.procurement.dto.CreatePurchaseOrderRequest;
import com.oriah.procurement.dto.PurchaseOrderLineRequest;
import com.oriah.procurement.dto.PurchaseOrderResponse;
import com.oriah.procurement.dto.UpdatePurchaseOrderRequest;
import com.oriah.procurement.exception.InvalidFieldsException;
import com.oriah.procurement.model.PurchaseOrder;
import com.oriah.procurement.model.PurchaseOrder.NewLine;
import com.oriah.procurement.model.PurchaseOrderStatus;
import com.oriah.procurement.model.Supplier;
import com.oriah.procurement.repository.PurchaseOrderRepository;
import com.oriah.procurement.repository.SupplierRepository;

/**
 * Placing, reading, changing and cancelling purchase orders.
 *
 * <h2>The supplier is locked, not just looked at</h2>
 *
 * What COMMITS Oriah to a supplier (placing an order, adding to one) takes the supplier row
 * SHARE-locked and checks the supplier is in use. Deleting, stopping or editing a supplier
 * takes the same row exclusively. So "a supplier we have stopped gets no new orders" and "a
 * supplier with orders is not deleted" hold when two requests race: one waits for the other,
 * then sees what it did. Receiving and cancelling an order that already exists need no active
 * supplier: the goods were ordered, or the order is being withdrawn.
 *
 * <h2>Catalog is asked once, when a line is added or changed</h2>
 *
 * Eligibility (the variant exists, is switched on, and belongs to a product that is not deleted
 * or archived) is judged when the line is placed and the answer's names are copied onto the line.
 * Receiving makes no catalog call: goods already ordered and paid for are never stranded because
 * a product was archived in between. (Archiving after the check is a race no check closes.)
 *
 * <h2>Every change names the version it was made from</h2>
 *
 * Checked after the order's row is locked, so the version compared is the latest.
 */
@Service
@Transactional
public class PurchaseOrderService {

    private static final Logger log = LoggerFactory.getLogger(PurchaseOrderService.class);

    /** Lusaka's calendar decides what "today" is for a business date. */
    static final ZoneId BUSINESS_ZONE = ZoneId.of("Africa/Lusaka");

    private final PurchaseOrderRepository orders;
    private final SupplierRepository suppliers;
    private final CatalogVariants catalog;

    @PersistenceContext
    private EntityManager entityManager;

    public PurchaseOrderService(PurchaseOrderRepository orders, SupplierRepository suppliers, CatalogVariants catalog) {
        this.orders = orders;
        this.suppliers = suppliers;
        this.catalog = catalog;
    }

    static LocalDate today() {
        return LocalDate.now(BUSINESS_ZONE);
    }

    // ------------------------------------------------------------------ place

    public PurchaseOrderResponse place(CreatePurchaseOrderRequest request) {
        Supplier supplier = suppliers.findLiveForShare(request.supplierId())
                .orElseThrow(() -> new ResourceNotFoundException("Supplier", "id", request.supplierId().toString()));
        requireInUse(supplier);

        List<NewLine> lines = resolveLines(request.lines());
        PurchaseOrder order = new PurchaseOrder(supplier.id(), request.placedOn(), request.note(), lines, today());
        orders.saveAndFlush(order);
        log.info("Placed purchase order id={} supplier={} lines={}", order.id(), supplier.id(), lines.size());
        return PurchaseOrderResponse.of(order, supplier);
    }

    // ------------------------------------------------------------------- read

    @Transactional(readOnly = true)
    public PurchaseOrderResponse get(UUID id) {
        return respond(orders.findById(id).orElseThrow(() -> notFound(id)));
    }

    @Transactional(readOnly = true)
    public Page<PurchaseOrderResponse> list(int page, int size, UUID supplierId, PurchaseOrderStatus status) {
        var pageable = PageRequest.of(page, size);
        Page<PurchaseOrder> found;
        if (supplierId != null && status != null) {
            found = orders.findBySupplierAndStatus(supplierId, status, pageable);
        } else if (supplierId != null) {
            found = orders.findBySupplier(supplierId, pageable);
        } else if (status != null) {
            found = orders.findByStatus(status, pageable);
        } else {
            found = orders.findNewestFirst(pageable);
        }
        return found.map(this::respond);
    }

    // ----------------------------------------------------------------- change

    public PurchaseOrderResponse update(UUID id, UpdatePurchaseOrderRequest request) {
        PurchaseOrder order = lockedAtVersion(id, request.version());

        boolean headerChanged = false;
        if (request.placedOn() != null || request.note() != null) {
            headerChanged = order.changeHeader(request.placedOn() != null ? request.placedOn() : order.placedOn(),
                    request.note() != null ? request.note() : order.note(), today());
        }
        if (request.lines() != null) {
            // Adding to an order is a new commitment to the supplier: it needs one in use.
            Supplier supplier = suppliers.findLiveForShare(order.supplierId())
                    .orElseThrow(() -> new ResourceNotFoundException("Supplier", "id", order.supplierId().toString()));
            requireInUse(supplier);
            order.replaceLines(resolveLines(request.lines()));
            if (!headerChanged) {
                // The lines are the inverse side of the relationship, so replacing them alone
                // leaves the order's own row untouched and its version where it was: a change
                // someone else read before this one would then be accepted. Forcing the
                // increment (now, so the answer carries the new version) is what makes
                // "replace the lines" move the version like every other change. When the
                // header changed too, the order is dirty and increments by itself.
                entityManager.lock(order, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
            }
        }
        orders.saveAndFlush(order);
        return respond(order);
    }

    public PurchaseOrderResponse cancel(UUID id, long version) {
        PurchaseOrder order = lockedAtVersion(id, version);
        order.cancel();
        orders.saveAndFlush(order);
        log.info("Cancelled purchase order id={}", id);
        return respond(order);
    }

    // ---------------------------------------------------------------- helpers

    private PurchaseOrder lockedAtVersion(UUID id, long version) {
        PurchaseOrder order = orders.findByIdForUpdate(id).orElseThrow(() -> notFound(id));
        if (!Objects.equals(version, order.version())) {
            throw new ObjectOptimisticLockingFailureException(PurchaseOrder.class, id);
        }
        return order;
    }

    private static void requireInUse(Supplier supplier) {
        if (!supplier.isActive()) {
            throw new IllegalStateException("Supplier '" + supplier.name() + "' is stopped: Oriah no longer places "
                    + "orders with it. Start it again to add to its orders.");
        }
    }

    private static ResourceNotFoundException notFound(UUID id) {
        return new ResourceNotFoundException("PurchaseOrder", "id", id.toString());
    }

    private PurchaseOrderResponse respond(PurchaseOrder order) {
        // findById, not the live lookup: an order still names a supplier that has since been deleted.
        Supplier supplier = suppliers.findById(order.supplierId())
                .orElseThrow(() -> new IllegalStateException("purchase order " + order.id() + " names a supplier that is gone"));
        return PurchaseOrderResponse.of(order, supplier);
    }

    /**
     * Asks catalog about every variant at once, refuses the ones that cannot be ordered (all the
     * problems together, each against its line), and gives back the lines with the catalogue's names.
     */
    private List<NewLine> resolveLines(List<PurchaseOrderLineRequest> requested) {
        Map<UUID, Entry> known = catalog.lookup(requested.stream().map(PurchaseOrderLineRequest::variantId).toList());

        List<FieldErrorView> problems = new ArrayList<>();
        List<NewLine> lines = new ArrayList<>();
        for (int i = 0; i < requested.size(); i++) {
            PurchaseOrderLineRequest line = requested.get(i);
            Entry entry = known.get(line.variantId());
            String field = "lines[" + i + "].variantId";
            if (entry == null || !entry.found()) {
                problems.add(new FieldErrorView(field, "no such variant in the catalogue"));
            } else if (Boolean.TRUE.equals(entry.productDeleted())) {
                problems.add(new FieldErrorView(field, "belongs to a deleted product"));
            } else if ("ARCHIVED".equals(entry.productStatus())) {
                problems.add(new FieldErrorView(field, "belongs to an archived product"));
            } else if (!Boolean.TRUE.equals(entry.active())) {
                problems.add(new FieldErrorView(field, "is switched off in the catalogue"));
            } else {
                lines.add(new NewLine(line.variantId(), line.quantity(), line.totalPaid(),
                        entry.sku(), entry.productTitle(), entry.variantTitle(), entry.kind()));
            }
        }
        if (!problems.isEmpty()) {
            throw new InvalidFieldsException(problems);
        }
        return lines;
    }
}

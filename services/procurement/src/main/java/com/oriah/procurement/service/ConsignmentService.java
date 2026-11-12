package com.oriah.procurement.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

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
import com.oriah.procurement.allocation.ConsignmentCosting;
import com.oriah.procurement.dto.AllocationPreviewResponse;
import com.oriah.procurement.dto.AttachOrderRequest;
import com.oriah.procurement.dto.ConsignmentResponse;
import com.oriah.procurement.dto.ConsignmentSummary;
import com.oriah.procurement.dto.CostLineRequest;
import com.oriah.procurement.dto.CostLinesRequest;
import com.oriah.procurement.dto.CreateConsignmentRequest;
import com.oriah.procurement.dto.LineDetailsRequest;
import com.oriah.procurement.dto.OpeningLineRequest;
import com.oriah.procurement.dto.OpeningLinesRequest;
import com.oriah.procurement.dto.UpdateConsignmentRequest;
import com.oriah.procurement.exception.InvalidFieldsException;
import com.oriah.procurement.model.Consignment;
import com.oriah.procurement.model.Consignment.CostLineInput;
import com.oriah.procurement.model.Consignment.Header;
import com.oriah.procurement.model.Consignment.OpeningLine;
import com.oriah.procurement.model.ConsignmentLine;
import com.oriah.procurement.model.ConsignmentStatus;
import com.oriah.procurement.model.CostType;
import com.oriah.procurement.model.PurchaseOrder;
import com.oriah.procurement.model.Supplier;
import com.oriah.procurement.repository.ConsignmentRepository;
import com.oriah.procurement.repository.PurchaseOrderRepository;
import com.oriah.procurement.repository.SupplierRepository;

/**
 * Building a consignment: starting one from whole purchase orders (or typing in the opening
 * stock), keying its costs, attaching and detaching orders, deleting a draft, and previewing
 * what it would cost per unit.
 *
 * <h2>Every change names the version it was made from</h2>
 *
 * The consignment's row is locked first ({@code SELECT ... FOR UPDATE}) and the version is
 * checked after, so the version compared is the latest and two changes run one after the other.
 * Changing only the lines or the costs touches no column of the consignment's own row (they are
 * the inverse side of the relationship), so those changes force the version up
 * ({@code PESSIMISTIC_FORCE_INCREMENT}); without it a change someone else read before this one
 * would be accepted. A header change that really changes something moves the version by itself.
 *
 * <h2>Lock order</h2>
 *
 * Consignment first, then purchase orders in id order. Anything that locks only an order (editing,
 * cancelling it) holds one lock and so cannot be part of a cycle. Two requests that name the same
 * order are serialized by the order's row: the second finds it attached (or its version moved on)
 * and is refused with a 409.
 *
 * <p>Catalog is asked only when opening lines are typed (the same rule as a purchase order line);
 * attaching an order asks nothing, because its lines were checked when it was placed.
 */
@Service
@Transactional
public class ConsignmentService {

    private static final Logger log = LoggerFactory.getLogger(ConsignmentService.class);

    private final ConsignmentRepository consignments;
    private final PurchaseOrderRepository orders;
    private final SupplierRepository suppliers;
    private final OrderableVariants variants;

    @PersistenceContext
    private EntityManager entityManager;

    public ConsignmentService(ConsignmentRepository consignments, PurchaseOrderRepository orders,
                              SupplierRepository suppliers, OrderableVariants variants) {
        this.consignments = consignments;
        this.orders = orders;
        this.suppliers = suppliers;
        this.variants = variants;
    }

    // ----------------------------------------------------------------- create

    public ConsignmentResponse create(CreateConsignmentRequest request) {
        boolean opening = Boolean.TRUE.equals(request.opening());
        boolean hasOrders = request.purchaseOrders() != null && !request.purchaseOrders().isEmpty();
        boolean hasLines = request.lines() != null && !request.lines().isEmpty();
        if (opening) {
            if (hasOrders) {
                throw new IllegalArgumentException("the opening consignment has no purchase orders: its stock is typed in");
            }
            if (!hasLines) {
                throw new IllegalArgumentException("the opening consignment needs at least one line");
            }
            return createOpening(request);
        }
        if (hasLines) {
            throw new IllegalArgumentException("lines are typed only for the opening consignment (set opening to "
                    + "true): the lines of any other consignment come from its purchase orders");
        }
        if (!hasOrders) {
            throw new IllegalArgumentException("a consignment needs at least one purchase order");
        }
        return createFromOrders(request);
    }

    private ConsignmentResponse createFromOrders(CreateConsignmentRequest request) {
        List<CreateConsignmentRequest.OrderRef> refs = request.purchaseOrders();
        Set<UUID> seen = new HashSet<>();
        for (CreateConsignmentRequest.OrderRef ref : refs) {
            if (!seen.add(ref.id())) {
                throw new IllegalArgumentException("the same purchase order appears more than once: " + ref.id());
            }
        }

        // Locked in the database's id order, whatever order the person listed them in.
        Map<UUID, PurchaseOrder> locked = orders.findAllByIdForUpdate(seen).stream()
                .collect(Collectors.toMap(PurchaseOrder::id, Function.identity()));

        Consignment consignment = Consignment.draft(request.note());
        for (CreateConsignmentRequest.OrderRef ref : refs) {
            PurchaseOrder order = locked.get(ref.id());
            if (order == null) {
                throw new ResourceNotFoundException("PurchaseOrder", "id", ref.id().toString());
            }
            if (!Objects.equals(ref.version(), order.version())) {
                throw new ObjectOptimisticLockingFailureException(PurchaseOrder.class, order.id());
            }
            consignment.attach(order);
        }
        consignments.saveAndFlush(consignment);
        log.info("Created consignment id={} from {} purchase orders, {} lines", consignment.id(), refs.size(),
                consignment.lines().size());
        return respond(consignment);
    }

    private ConsignmentResponse createOpening(CreateConsignmentRequest request) {
        Consignment consignment = Consignment.opening(request.note(), openingLines(request.lines()));
        consignments.saveAndFlush(consignment);
        log.info("Created the opening consignment id={} with {} lines", consignment.id(), consignment.lines().size());
        return respond(consignment);
    }

    // ------------------------------------------------------------------- read

    @Transactional(readOnly = true)
    public ConsignmentResponse get(UUID id) {
        return respond(consignments.findById(id).orElseThrow(() -> notFound(id)));
    }

    @Transactional(readOnly = true)
    public Page<ConsignmentSummary> list(int page, int size, ConsignmentStatus status, Boolean opening) {
        var pageable = PageRequest.of(page, size);
        Page<Consignment> found;
        if (status != null && opening != null) {
            found = consignments.findByStatusAndOpening(status, opening, pageable);
        } else if (status != null) {
            found = consignments.findByStatus(status, pageable);
        } else if (opening != null) {
            found = consignments.findByOpening(opening, pageable);
        } else {
            found = consignments.findNewestFirst(pageable);
        }
        return found.map(ConsignmentSummary::of);
    }

    /** What the consignment would cost per unit if it were received now. Changes nothing. */
    @Transactional(readOnly = true)
    public AllocationPreviewResponse preview(UUID id) {
        Consignment consignment = consignments.findById(id).orElseThrow(() -> notFound(id));
        return AllocationPreviewResponse.of(ConsignmentCosting.of(consignment));
    }

    // ----------------------------------------------------------------- change

    /** Replaces the header as a whole. A save that changes nothing writes nothing, so the version holds. */
    public ConsignmentResponse updateHeader(UUID id, UpdateConsignmentRequest request) {
        Consignment consignment = lockedAtVersion(id, request.version());
        boolean estimated = request.estimated() != null ? request.estimated() : consignment.isEstimated();
        boolean changed = consignment.changeHeader(new Header(request.note(), estimated, request.chargesBasis(),
                request.declarant(), request.clearanceForm(), request.zraAssessmentRef(), request.licensedAgentUsed()));
        if (changed) {
            consignments.saveAndFlush(consignment);
        }
        return respond(consignment);
    }

    public ConsignmentResponse updateLine(UUID id, UUID lineId, LineDetailsRequest request) {
        Consignment consignment = lockedAtVersion(id, request.version());
        if (consignment.lines().stream().noneMatch(l -> l.id().equals(lineId))) {
            throw new ResourceNotFoundException("ConsignmentLine", "id", lineId.toString());
        }
        consignment.changeLineDetails(lineId, request.chargeableWeightGrams(), request.originCountry(), request.hsCode());
        return saved(consignment);
    }

    public ConsignmentResponse replaceOpeningLines(UUID id, OpeningLinesRequest request) {
        Consignment consignment = lockedAtVersion(id, request.version());
        consignment.replaceOpeningLines(openingLines(request.lines()));
        return saved(consignment);
    }

    public ConsignmentResponse replaceCostLines(UUID id, CostLinesRequest request) {
        Consignment consignment = lockedAtVersion(id, request.version());
        consignment.replaceCostLines(costLineInputs(request.costLines()));
        return saved(consignment);
    }

    public ConsignmentResponse attachOrder(UUID id, AttachOrderRequest request) {
        Consignment consignment = lockedAtVersion(id, request.version());
        PurchaseOrder order = orders.findByIdForUpdate(request.purchaseOrderId())
                .orElseThrow(() -> new ResourceNotFoundException("PurchaseOrder", "id", request.purchaseOrderId().toString()));
        if (!Objects.equals(request.purchaseOrderVersion(), order.version())) {
            throw new ObjectOptimisticLockingFailureException(PurchaseOrder.class, order.id());
        }
        consignment.attach(order);
        return saved(consignment);
    }

    public ConsignmentResponse detachOrder(UUID id, UUID orderId, long version) {
        Consignment consignment = lockedAtVersion(id, version);
        PurchaseOrder order = orders.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("PurchaseOrder", "id", orderId.toString()));
        consignment.detach(order);
        return saved(consignment);
    }

    /** Deletes a DRAFT with its lines and costs, and sets its orders free. A received consignment is history. */
    public void deleteDraft(UUID id, long version) {
        Consignment consignment = lockedAtVersion(id, version);
        if (!consignment.isDraft()) {
            throw new IllegalStateException("cannot delete a consignment that is " + consignment.status()
                    + ": it is history");
        }
        for (PurchaseOrder order : orders.findByConsignmentForUpdate(id)) {
            consignment.detach(order);
        }
        consignments.delete(consignment);
        consignments.flush();
        log.info("Deleted draft consignment id={}", id);
    }

    // ---------------------------------------------------------------- helpers

    private Consignment lockedAtVersion(UUID id, long version) {
        Consignment consignment = consignments.findByIdForUpdate(id).orElseThrow(() -> notFound(id));
        if (!Objects.equals(version, consignment.version())) {
            throw new ObjectOptimisticLockingFailureException(Consignment.class, id);
        }
        return consignment;
    }

    /**
     * After a change to the lines or the costs: the consignment's own row is untouched, so force
     * the version up (now, so the answer carries the new one), then write what changed.
     */
    private ConsignmentResponse saved(Consignment consignment) {
        entityManager.lock(consignment, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
        consignments.flush();
        return respond(consignment);
    }

    private static ResourceNotFoundException notFound(UUID id) {
        return new ResourceNotFoundException("Consignment", "id", id.toString());
    }

    private List<OpeningLine> openingLines(List<OpeningLineRequest> requested) {
        List<OrderableVariants.Orderable> resolved = variants.resolve(
                requested.stream().map(OpeningLineRequest::variantId).toList());
        List<OpeningLine> lines = new ArrayList<>(requested.size());
        for (int i = 0; i < requested.size(); i++) {
            OpeningLineRequest line = requested.get(i);
            OrderableVariants.Orderable variant = resolved.get(i);
            lines.add(new OpeningLine(line.variantId(), line.quantity(), line.totalCost(), line.chargeableWeightGrams(),
                    line.originCountry(), line.hsCode(), variant.sku(), variant.productTitle(), variant.variantTitle(),
                    variant.kind()));
        }
        return lines;
    }

    /** The cost types are read here so a refusal names the field: {@code costLines[i].costType}. */
    private List<CostLineInput> costLineInputs(List<CostLineRequest> requested) {
        List<FieldErrorView> problems = new ArrayList<>();
        List<CostLineInput> inputs = new ArrayList<>(requested.size());
        for (int i = 0; i < requested.size(); i++) {
            CostLineRequest line = requested.get(i);
            CostType type;
            try {
                type = CostType.parse(line.costType());
            } catch (IllegalArgumentException refused) {
                problems.add(new FieldErrorView("costLines[" + i + "].costType", refused.getMessage()));
                continue;
            }
            Map<UUID, java.math.BigDecimal> shares = null;
            if (line.shares() != null && !line.shares().isEmpty()) {
                shares = new LinkedHashMap<>();
                for (CostLineRequest.ShareRequest share : line.shares()) {
                    if (shares.put(share.lineId(), share.amount()) != null) {
                        problems.add(new FieldErrorView("costLines[" + i + "].shares",
                                "the same line appears more than once: " + share.lineId()));
                    }
                }
            }
            inputs.add(new CostLineInput(type, line.amount(), line.basis(), line.treatment(), line.incurredOn(),
                    line.note(), shares));
        }
        if (!problems.isEmpty()) {
            throw new InvalidFieldsException(problems);
        }
        return inputs;
    }

    private ConsignmentResponse respond(Consignment consignment) {
        List<PurchaseOrder> inIt = orders.findByConsignment(consignment.id());
        Set<UUID> supplierIds = new HashSet<>();
        inIt.forEach(o -> supplierIds.add(o.supplierId()));
        consignment.lines().stream().map(ConsignmentLine::supplierId).filter(Objects::nonNull).forEach(supplierIds::add);
        // findAllById, not the live lookup: a line still names a supplier that has since been deleted.
        Map<UUID, Supplier> byId = suppliers.findAllById(supplierIds).stream()
                .collect(Collectors.toMap(Supplier::id, Function.identity()));
        return ConsignmentResponse.of(consignment, inIt, byId);
    }
}

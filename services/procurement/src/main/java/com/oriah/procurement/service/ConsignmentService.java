package com.oriah.procurement.service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.observability.OriahExceptionHandlerSupport.FieldErrorView;
import com.oriah.observability.ResourceNotFoundException;
import com.oriah.procurement.allocation.ConsignmentCosting;
import com.oriah.procurement.allocation.ConsignmentCosting.LineBasis;
import com.oriah.procurement.allocation.ConsignmentCosting.LineView;
import com.oriah.procurement.dto.AllocationPreviewResponse;
import com.oriah.procurement.dto.AmendConsignmentRequest;
import com.oriah.procurement.dto.AmendmentResponse;
import com.oriah.procurement.dto.AttachOrderRequest;
import com.oriah.procurement.dto.ConsignmentResponse;
import com.oriah.procurement.dto.ConsignmentSummary;
import com.oriah.procurement.dto.CostLineRequest;
import com.oriah.procurement.dto.CostLinesRequest;
import com.oriah.procurement.dto.CreateConsignmentRequest;
import com.oriah.procurement.dto.LineDetailsRequest;
import com.oriah.procurement.dto.OpeningLineRequest;
import com.oriah.procurement.dto.OpeningLinesRequest;
import com.oriah.procurement.dto.RevisionSummary;
import com.oriah.procurement.dto.UpdateConsignmentRequest;
import com.oriah.procurement.events.ProcurementEvents;
import com.oriah.procurement.exception.InvalidFieldsException;
import com.oriah.procurement.model.Consignment;
import com.oriah.procurement.model.Consignment.CostLineInput;
import com.oriah.procurement.model.Consignment.Header;
import com.oriah.procurement.model.Consignment.OpeningLine;
import com.oriah.procurement.model.ConsignmentLine;
import com.oriah.procurement.model.ConsignmentStatus;
import com.oriah.procurement.model.CostLine;
import com.oriah.procurement.model.CostType;
import com.oriah.procurement.model.PurchaseOrder;
import com.oriah.procurement.model.Supplier;
import com.oriah.procurement.repository.ConsignmentRepository;
import com.oriah.procurement.repository.PurchaseOrderRepository;
import com.oriah.procurement.repository.SupplierRepository;
import com.oriah.procurement.service.ConsignmentAllocations.RevisionState;

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
    private final ReceiptGate receiptGate;
    private final ConsignmentAllocations allocations;
    private final ProcurementEvents events;
    private final Clock clock;

    @PersistenceContext
    private EntityManager entityManager;

    public ConsignmentService(ConsignmentRepository consignments, PurchaseOrderRepository orders,
                              SupplierRepository suppliers, OrderableVariants variants, ReceiptGate receiptGate,
                              ConsignmentAllocations allocations, ProcurementEvents events, Clock clock) {
        this.consignments = consignments;
        this.orders = orders;
        this.suppliers = suppliers;
        this.variants = variants;
        this.receiptGate = receiptGate;
        this.allocations = allocations;
        this.events = events;
        this.clock = clock;
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

    /**
     * What the consignment would cost per unit if it were received now; for a received one, the
     * allocation that was recorded when it landed. Changes nothing.
     */
    @Transactional(readOnly = true)
    public AllocationPreviewResponse preview(UUID id) {
        return preview(id, null);
    }

    /**
     * The same, for one recorded revision of a received consignment (1 is the receipt itself); null is the latest.
     * A draft has none recorded, so asking for a revision of it is a 404.
     */
    @Transactional(readOnly = true)
    public AllocationPreviewResponse preview(UUID id, Integer revision) {
        Consignment consignment = consignments.findById(id).orElseThrow(() -> notFound(id));
        if (consignment.status() == ConsignmentStatus.RECEIVED) {
            Optional<ConsignmentAllocations.Recorded> found = revision == null
                    ? allocations.latest(consignment) : allocations.at(consignment, revision);
            ConsignmentAllocations.Recorded recorded = found.orElseThrow(() -> revision == null
                    ? new IllegalStateException("received consignment " + id + " has no recorded allocation")
                    : new ResourceNotFoundException("AllocationRevision", "revision", revision.toString()));
            return AllocationPreviewResponse.of(recorded.outcome(), recorded.revision(), recorded.receivedAt());
        }
        if (revision != null) {
            throw new ResourceNotFoundException("AllocationRevision", "revision", revision.toString());
        }
        return AllocationPreviewResponse.of(ConsignmentCosting.of(consignment));
    }

    /** Every revision of a received consignment's allocation, oldest first; a draft has none. */
    @Transactional(readOnly = true)
    public List<RevisionSummary> revisions(UUID id) {
        Consignment consignment = consignments.findById(id).orElseThrow(() -> notFound(id));
        if (consignment.status() != ConsignmentStatus.RECEIVED) {
            return List.of();
        }
        return allocations.revisions(consignment).stream().map(RevisionSummary::of).toList();
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
        // Every override records the actor: a split that is new or changed is this person's.
        String typist = subject();
        consignment.stampNewlyTyped(typist == null ? "system" : typist, clock.instant().truncatedTo(ChronoUnit.MICROS));
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

    // ----------------------------------------------------------------- receive

    /**
     * The goods have landed. In ONE transaction: the allocation is worked out and recorded (revision
     * 1), every order in the consignment and the consignment itself become RECEIVED, and one
     * {@code consignment.received} per line is appended to the outbox, as the last write. If any step
     * fails nothing is left behind.
     *
     * <p>Locks, in this order and never another: the receipt gate (one receipt at a time, so receipt
     * order is time order), the consignment's row, its purchase orders in id order, and finally the
     * outbox's own lock when the events are appended.
     */
    public ConsignmentResponse receive(UUID id, long version) {
        receiptGate.enter();
        Consignment consignment = lockedAtVersion(id, version);
        if (!consignment.isDraft()) {
            throw new IllegalStateException("this consignment is already " + consignment.status());
        }
        List<PurchaseOrder> inIt = orders.findByConsignmentForUpdate(id);

        ConsignmentCosting.Outcome outcome = ConsignmentCosting.of(consignment);
        if (!outcome.ready()) {
            throw new IllegalStateException("The consignment cannot be received yet. " + String.join(" ", outcome.problems()));
        }
        if (consignment.isOpening() && receiptGate.anyShipmentReceived()) {
            throw new IllegalStateException("The opening stock must be received before any shipment: a shipment has "
                    + "already been received, so these goods would count as newer than it and be sold in the wrong "
                    + "order (oldest first). Record anything missed from the opening count as a normal consignment.");
        }

        Instant receivedAt = receiptTime();
        for (PurchaseOrder order : inIt) {
            order.markReceived();
        }
        consignment.markReceived(receivedAt);
        consignments.saveAndFlush(consignment);
        allocations.recordReceipt(consignment, outcome, subject());

        ConsignmentResponse response = respond(consignment);
        // Last: it takes the outbox's lock, which is held until commit.
        events.consignmentReceived(consignment, outcome, suppliersOf(consignment, inIt), actor());
        log.info("Received consignment id={} lines={} landed={}", id, consignment.lines().size(), outcome.landedTotal());
        return response;
    }

    /**
     * Now, to the microsecond the database keeps, and never earlier than the last receipt: the order
     * goods were received in is the order of these times, whatever the clock does.
     */
    private Instant receiptTime() {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Instant latest = receiptGate.latestReceipt();
        if (latest != null && !now.isAfter(latest)) {
            return latest.plus(1, ChronoUnit.MICROS);
        }
        return now;
    }

    // ------------------------------------------------------------------- amend

    /**
     * The cost of a received consignment was keyed wrong, or arrived late. The consignment is never edited: this
     * records a NEW revision of its allocation (with the reason and who made it), worked out from the revision before
     * it by exactly the rules a draft is costed by, and appends one {@code consignment.amended} for each line whose
     * unit costs, totals or estimate flag it changed, so inventory can re-lay the new costs over the stock. One
     * transaction: if any step fails nothing is left behind.
     *
     * <p>Made from the revision the person saw ({@code fromRevision}): the consignment's row is locked first, so two
     * corrections run one after the other and the second, finding a newer revision than it was made from, is a 409.
     * Locks, in this order: the consignment's row, then the outbox's own when the events are appended.
     */
    public AmendmentResponse amend(UUID id, AmendConsignmentRequest request) {
        Consignment consignment = consignments.findByIdForUpdate(id).orElseThrow(() -> notFound(id));
        if (consignment.status() != ConsignmentStatus.RECEIVED) {
            throw new IllegalStateException("this consignment is still " + consignment.status() + ": change it directly. Only "
                    + "a received consignment is corrected by a new revision");
        }
        int latest = allocations.latestRevision(id);
        if (request.fromRevision() != latest) {
            throw new ObjectOptimisticLockingFailureException(Consignment.class, id);
        }
        if (consignment.isOpening() && Boolean.FALSE.equals(request.estimated())) {
            throw new IllegalArgumentException("the opening stock is an estimate for good: its costs come from the old book, "
                    + "so it cannot be marked as no longer an estimate");
        }

        RevisionState previous = allocations.stateOf(consignment, latest);
        boolean estimated = request.estimated() != null ? request.estimated() : previous.estimated();

        // Never earlier than the revision before it, nor than the receipt, whatever the clock does.
        Instant amendedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        for (Instant floor : List.of(previous.createdAt(), consignment.receivedAt())) {
            if (!amendedAt.isAfter(floor)) {
                amendedAt = floor.plus(1, ChronoUnit.MICROS);
            }
        }
        String typist = subject();

        List<LineBasis> lines = withCorrectedGoods(previous.lines(), request.goodsLines());
        List<CostLine> costs = previous.costs();
        if (request.costLines() != null) {
            Set<UUID> lineIds = lines.stream().map(LineBasis::id).collect(Collectors.toSet());
            List<CostLine> built = consignment.checkedCostLinesForCorrection(costLineInputs(request.costLines()), lineIds);
            // A split that is sent again unchanged keeps its typist; a new or changed one is this person's.
            Consignment.carryStamps(previous.costs(), built);
            Consignment.stampNewlyTyped(built, typist == null ? "system" : typist, amendedAt);
            costs = built;
        }
        if (sameInputs(previous, lines, costs, estimated)) {
            throw new IllegalArgumentException("This correction changes nothing: the goods, the costs and the estimate "
                    + "are as revision " + latest + " has them. Say what was wrong.");
        }

        ConsignmentCosting.Outcome outcome = ConsignmentCosting.of(lines, costs);
        if (!outcome.ready()) {
            throw new IllegalArgumentException("The correction cannot be costed. " + String.join(" ", outcome.problems()));
        }

        // Which lines the correction moved, against what revision `latest` recorded.
        Map<UUID, String> was = ConsignmentAllocations.figuresOf(allocations.at(consignment, latest).orElseThrow().outcome());
        Map<UUID, String> now = ConsignmentAllocations.figuresOf(outcome);
        boolean estimateMoved = previous.estimated() != estimated;
        Set<UUID> changed = new LinkedHashSet<>();
        for (LineView line : outcome.lines()) {
            if (estimateMoved || !now.get(line.lineId()).equals(was.get(line.lineId()))) {
                changed.add(line.lineId());
            }
        }

        int revision = latest + 1;
        String reason = request.reason().strip();
        allocations.record(id, revision, reason, estimated, lines, costs, outcome, typist, amendedAt);

        // Last: it takes the outbox's lock, which is held until commit.
        events.consignmentAmended(consignment, outcome, suppliersOf(consignment, orders.findByConsignment(id)), actor(), revision,
                reason, estimated, changed, amendedAt);
        log.info("Amended consignment id={} to revision {}: {} of {} lines changed, landed={}", id, revision, changed.size(),
                lines.size(), outcome.landedTotal());
        return new AmendmentResponse(revision, reason, typist, amendedAt, estimated,
                AllocationPreviewResponse.of(outcome, revision, consignment.receivedAt()), List.copyOf(changed));
    }

    /** The lines with the goods totals the person corrected; a line they did not name keeps its own. */
    private static List<LineBasis> withCorrectedGoods(List<LineBasis> lines, List<AmendConsignmentRequest.GoodsLineRequest> corrections) {
        if (corrections == null || corrections.isEmpty()) {
            return lines;
        }
        Map<UUID, BigDecimal> byLine = new LinkedHashMap<>();
        Set<UUID> known = lines.stream().map(LineBasis::id).collect(Collectors.toSet());
        for (AmendConsignmentRequest.GoodsLineRequest correction : corrections) {
            if (!known.contains(correction.lineId())) {
                throw new IllegalArgumentException("goodsLines names a line that is not in this consignment: " + correction.lineId());
            }
            if (correction.goodsTotal().signum() <= 0) {
                throw new IllegalArgumentException("goods are never free: the goods total of line " + correction.lineId() + " must be above zero");
            }
            if (byLine.put(correction.lineId(), correction.goodsTotal()) != null) {
                throw new IllegalArgumentException("the same line appears more than once in goodsLines: " + correction.lineId());
            }
        }
        return lines.stream().map(l -> byLine.containsKey(l.id())
                ? new LineBasis(l.id(), l.position(), l.variantId(), l.sku(), l.quantity(), byLine.get(l.id()), l.chargeableWeightGrams())
                : l).toList();
    }

    /** True when the correction asks for exactly what the revision before already has: goods, every cost and the estimate. */
    private static boolean sameInputs(RevisionState previous, List<LineBasis> lines, List<CostLine> costs, boolean estimated) {
        if (previous.estimated() != estimated || previous.lines().size() != lines.size() || previous.costs().size() != costs.size()) {
            return false;
        }
        for (int i = 0; i < lines.size(); i++) {
            if (previous.lines().get(i).goodsTotal().compareTo(lines.get(i).goodsTotal()) != 0) {
                return false;
            }
        }
        for (int i = 0; i < costs.size(); i++) {
            if (!costSignature(previous.costs().get(i), lines).equals(costSignature(costs.get(i), lines))) {
                return false;
            }
        }
        return true;
    }

    /** A cost as one comparable string: what it is, how much, how it is shared, and the split a person typed, in line order. */
    private static String costSignature(CostLine cost, List<LineBasis> lines) {
        StringBuilder signature = new StringBuilder().append(cost.costType()).append('|').append(plain(cost.amount())).append('|')
                .append(cost.basis()).append('|').append(cost.treatment()).append('|').append(cost.incurredOn()).append('|')
                .append(cost.note()).append('|');
        for (LineBasis line : lines) {
            BigDecimal share = cost.shares().get(line.id());
            signature.append(share == null ? "-" : plain(share)).append(',');
        }
        return signature.toString();
    }

    private static String plain(BigDecimal amount) {
        return amount.signum() == 0 ? "0" : amount.stripTrailingZeros().toPlainString();
    }

    /** The Keycloak subject of the signed-in person, or null outside a request. */
    private static String subject() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth instanceof JwtAuthenticationToken jwt ? jwt.getToken().getSubject() : null;
    }

    private static String actor() {
        String subject = subject();
        return subject == null ? "system:procurement" : "user:" + subject;
    }

    // ---------------------------------------------------------------- helpers

    private Map<UUID, Supplier> suppliersOf(Consignment consignment, List<PurchaseOrder> inIt) {
        Set<UUID> supplierIds = new HashSet<>();
        inIt.forEach(o -> supplierIds.add(o.supplierId()));
        consignment.lines().stream().map(ConsignmentLine::supplierId).filter(Objects::nonNull).forEach(supplierIds::add);
        // findAllById, not the live lookup: a line still names a supplier that has since been deleted.
        return suppliers.findAllById(supplierIds).stream().collect(Collectors.toMap(Supplier::id, Function.identity()));
    }

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

    /**
     * The consignment as it stands. A received one carries the revision of its allocation that stands; after a
     * correction its lines' goods and its cost lines are the ones that revision was worked out from, because the
     * consignment's own rows are frozen as they were received.
     */
    private ConsignmentResponse respond(Consignment consignment) {
        List<PurchaseOrder> inIt = orders.findByConsignment(consignment.id());
        Map<UUID, Supplier> named = suppliersOf(consignment, inIt);
        if (consignment.status() == ConsignmentStatus.RECEIVED) {
            int latest = allocations.latestRevision(consignment.id());
            if (latest > 1) {
                return ConsignmentResponse.of(consignment, inIt, named, latest, allocations.stateOf(consignment, latest));
            }
            return ConsignmentResponse.of(consignment, inIt, named, latest == 0 ? null : latest, null);
        }
        return ConsignmentResponse.of(consignment, inIt, named);
    }
}

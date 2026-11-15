package com.oriah.procurement.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

import com.oriah.jpa.BaseEntity;
import com.oriah.money.Ngwee;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * One shipment, and the costs shared across it.
 *
 * <h2>What goes in</h2>
 *
 * A normal consignment is built from WHOLE purchase orders: a forwarder consolidates several
 * orders, placed with one supplier each weeks apart, into one shipment under one courier
 * bill. There are no part-shipments and no short or extra deliveries (client Q4-Q6), so an
 * order is in a consignment entire or not at all. Attaching an order copies its lines onto the
 * consignment and FREEZES the order, so the copy cannot drift from what was ordered; detaching
 * takes the lines out again and sets the order free.
 *
 * <p>The OPENING consignment is the stock the shop already held on day one, typed in from the
 * client's old book: no order, no supplier, always an estimate, and no line may cost K0.
 *
 * <h2>Costs</h2>
 *
 * A cost line says what it is (a closed list: never a bank or transfer charge), how much, how
 * it is shared between the lines ({@link CostBasis}) and what happens to the money
 * ({@link CostTreatment}). The split can be typed per line. Working out the unit costs is the
 * allocator's job and is done when the goods land.
 *
 * <h2>Rules held here</h2>
 * <ul>
 *   <li>at most 20 orders, 200 lines and 30 cost lines;</li>
 *   <li>a consignment is changed only while it is a DRAFT; RECEIVED is the end;</li>
 *   <li>when the charges basis says {@code DUTY_PAID} (the forwarder's price already includes
 *       duty), no duty, surtax or import VAT line may be above zero, so it is not counted
 *       twice;</li>
 *   <li>typed shares name only this consignment's lines, none negative, and when given they
 *       cover every line and add up to the cost exactly.</li>
 * </ul>
 * A refused input is an {@code IllegalArgumentException} (400); a refused state an
 * {@code IllegalStateException} (409). The aggregate is untouched either way.
 */
@Entity
@Table(name = "consignment")
public class Consignment extends BaseEntity {

    public static final int MAX_ORDERS = 20;
    public static final int MAX_LINES = 200;
    public static final int MAX_COST_LINES = 30;
    public static final int MAX_NOTE = 500;
    public static final int MAX_DECLARANT = 120;
    public static final int MAX_ZRA_REFERENCE = 80;
    public static final int MAX_COST_NOTE = 200;

    /**
     * A line of the opening consignment as it is asked for. The names are what the catalogue
     * calls the variant now, kept so history does not change when a product is renamed.
     */
    public record OpeningLine(UUID variantId, int quantity, BigDecimal goodsTotal, Long chargeableWeightGrams,
                              String originCountry, String hsCode,
                              String sku, String productTitle, String variantTitle, String kind) {
    }

    /** Every editable header field, as the new whole. Blank text or null clears a field. */
    public record Header(String note, boolean estimated, ChargesBasis chargesBasis, String declarant,
                         ClearanceForm clearanceForm, String zraAssessmentRef, Boolean licensedAgentUsed) {
    }

    /**
     * A cost line as it is asked for. {@code basis} null means the type's default,
     * {@code treatment} null means CAPITALIZE, {@code shares} null or empty means "not typed".
     */
    public record CostLineInput(CostType costType, BigDecimal amount, CostBasis basis, CostTreatment treatment,
                                LocalDate incurredOn, String note, Map<UUID, BigDecimal> shares) {
    }

    @Id
    private UUID id;

    /** The number a person quotes: C-000045. Assigned by the database. */
    @Generated(event = EventType.INSERT)
    @Column(name = "ref_no", insertable = false, updatable = false)
    private Long refNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ConsignmentStatus status;

    @Column(name = "is_opening", nullable = false, updatable = false)
    private boolean opening;

    @Column(name = "is_estimated", nullable = false)
    private boolean estimated;

    @Column(length = MAX_NOTE)
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(name = "charges_basis", length = 12)
    private ChargesBasis chargesBasis;

    @Column(length = MAX_DECLARANT)
    private String declarant;

    @Enumerated(EnumType.STRING)
    @Column(name = "clearance_form", length = 4)
    private ClearanceForm clearanceForm;

    @Column(name = "zra_assessment_ref", length = MAX_ZRA_REFERENCE)
    private String zraAssessmentRef;

    @Column(name = "licensed_agent_used")
    private Boolean licensedAgentUsed;

    @Column(name = "received_at")
    private Instant receivedAt;

    /** A wrapper type, null until saved: how Spring Data sees a consignment whose id we assigned as new. */
    @Version
    @Column(nullable = false)
    private Long version;

    @OneToMany(mappedBy = "consignment", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position asc")
    private List<ConsignmentLine> lines = new ArrayList<>();

    @OneToMany(mappedBy = "consignment", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position asc")
    private List<CostLine> costLines = new ArrayList<>();

    protected Consignment() {
        // JPA
    }

    private Consignment(boolean opening, String note) {
        this.id = UUID.randomUUID();
        this.status = ConsignmentStatus.DRAFT;
        this.opening = opening;
        this.estimated = opening;
        this.note = checkedText(note, MAX_NOTE, "note");
    }

    /** An empty draft shipment, to which whole purchase orders are attached. */
    public static Consignment draft(String note) {
        return new Consignment(false, note);
    }

    /** The day-one stock: typed in, no supplier, no order, always an estimate. */
    public static Consignment opening(String note, List<OpeningLine> typed) {
        Consignment consignment = new Consignment(true, note);
        consignment.setOpeningLines(checkedOpeningLines(typed));
        return consignment;
    }

    // ------------------------------------------------------------------ reads

    public UUID id() {
        return id;
    }

    public Long refNo() {
        return refNo;
    }

    public ConsignmentStatus status() {
        return status;
    }

    public boolean isDraft() {
        return status == ConsignmentStatus.DRAFT;
    }

    public boolean isOpening() {
        return opening;
    }

    public boolean isEstimated() {
        return estimated;
    }

    public String note() {
        return note;
    }

    public ChargesBasis chargesBasis() {
        return chargesBasis;
    }

    public String declarant() {
        return declarant;
    }

    public ClearanceForm clearanceForm() {
        return clearanceForm;
    }

    public String zraAssessmentRef() {
        return zraAssessmentRef;
    }

    public Boolean licensedAgentUsed() {
        return licensedAgentUsed;
    }

    /** When the goods landed; null while a draft. */
    public Instant receivedAt() {
        return receivedAt;
    }

    public Long version() {
        return version;
    }

    public List<ConsignmentLine> lines() {
        return List.copyOf(lines);
    }

    public List<CostLine> costLines() {
        return List.copyOf(costLines);
    }

    /** How many whole purchase orders are in this consignment. */
    public int orderCount() {
        return (int) lines.stream().map(ConsignmentLine::purchaseOrderId).filter(Objects::nonNull).distinct().count();
    }

    // ---------------------------------------------------------------- orders

    /** Puts a whole OPEN purchase order into this draft: its lines are copied, and it is frozen. */
    public void attach(PurchaseOrder order) {
        requireDraft("attach a purchase order to");
        if (opening) {
            throw new IllegalStateException("the opening consignment has no purchase orders: its stock was typed in");
        }
        if (orderCount() + 1 > MAX_ORDERS) {
            throw new IllegalArgumentException("a consignment holds at most " + MAX_ORDERS + " purchase orders");
        }
        if (lines.size() + order.lines().size() > MAX_LINES) {
            throw new IllegalArgumentException("a consignment holds at most " + MAX_LINES + " lines; adding this order "
                    + "would make " + (lines.size() + order.lines().size()));
        }
        // The first change, and the one that can refuse (cancelled, or in a consignment already).
        order.attachTo(id);
        int position = lines.size();
        for (PurchaseOrderLine source : order.lines()) {
            lines.add(new ConsignmentLine(this, ++position, order.supplierId(), order.id(), source));
        }
    }

    /** Takes a purchase order and its lines back out of this draft, and sets the order free. */
    public void detach(PurchaseOrder order) {
        requireDraft("detach a purchase order from");
        if (!id.equals(order.consignmentId())) {
            throw new IllegalStateException("purchase order is not in this consignment");
        }
        Set<UUID> removed = new HashSet<>();
        lines.removeIf(line -> {
            boolean ofThisOrder = order.id().equals(line.purchaseOrderId());
            if (ofThisOrder) {
                removed.add(line.id());
            }
            return ofThisOrder;
        });
        costLines.forEach(cost -> cost.dropSharesOf(removed));
        renumberLines();
        order.detach();
    }

    // ------------------------------------------------------ the opening lines

    /** Replaces every typed line of the opening consignment. What was typed per line for a cost goes too. */
    public void replaceOpeningLines(List<OpeningLine> typed) {
        requireDraft("change the lines of");
        if (!opening) {
            throw new IllegalStateException("only the opening consignment has typed lines; the lines of "
                    + "this one come from its purchase orders");
        }
        List<OpeningLine> checked = checkedOpeningLines(typed);
        costLines.forEach(CostLine::clearShares);
        setOpeningLines(checked);
    }

    // ----------------------------------------------------------------- header

    /** @return whether anything actually changed */
    public boolean changeHeader(Header header) {
        requireDraft("change");
        if (opening && !header.estimated()) {
            throw new IllegalArgumentException("the opening consignment is always an estimate: its costs come from "
                    + "the old book, not from an invoice");
        }
        String newNote = checkedText(header.note(), MAX_NOTE, "note");
        String newDeclarant = checkedText(header.declarant(), MAX_DECLARANT, "declarant");
        String newZra = checkedText(header.zraAssessmentRef(), MAX_ZRA_REFERENCE, "ZRA assessment reference");
        if (header.chargesBasis() == ChargesBasis.DUTY_PAID) {
            for (CostLine cost : costLines) {
                if (cost.costType().isCustoms() && cost.amount().signum() > 0) {
                    throw new IllegalArgumentException("charges basis DUTY_PAID says the forwarder's price already "
                            + "includes duty, but cost line " + cost.position() + " (" + cost.costType()
                            + ") is above zero: set that line to zero, or choose FREIGHT_ONLY");
                }
            }
        }
        boolean changed = !Objects.equals(newNote, note)
                || header.estimated() != estimated
                || header.chargesBasis() != chargesBasis
                || !Objects.equals(newDeclarant, declarant)
                || header.clearanceForm() != clearanceForm
                || !Objects.equals(newZra, zraAssessmentRef)
                || !Objects.equals(header.licensedAgentUsed(), licensedAgentUsed);
        this.note = newNote;
        this.estimated = header.estimated();
        this.chargesBasis = header.chargesBasis();
        this.declarant = newDeclarant;
        this.clearanceForm = header.clearanceForm();
        this.zraAssessmentRef = newZra;
        this.licensedAgentUsed = header.licensedAgentUsed();
        return changed;
    }

    /** The chargeable weight, origin country and HS code of one line; blank or null clears each. */
    public void changeLineDetails(UUID lineId, Long weightGrams, String originCountry, String hsCode) {
        requireDraft("change");
        ConsignmentLine line = lines.stream().filter(l -> l.id().equals(lineId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no such line in this consignment"));
        line.changeDetails(weightGrams, originCountry, hsCode);
    }

    // ------------------------------------------------------------- cost lines

    /** Replaces every cost line. Nothing changes unless every one is acceptable. */
    public void replaceCostLines(List<CostLineInput> inputs) {
        requireDraft("change the costs of");
        if (inputs == null) {
            throw new IllegalArgumentException("cost lines are required (an empty list clears them)");
        }
        if (inputs.size() > MAX_COST_LINES) {
            throw new IllegalArgumentException("a consignment holds at most " + MAX_COST_LINES + " cost lines");
        }
        Set<UUID> lineIds = new HashSet<>();
        lines.forEach(l -> lineIds.add(l.id()));

        List<CostLine> built = new ArrayList<>(inputs.size());
        for (int i = 0; i < inputs.size(); i++) {
            built.add(checkedCostLine(i + 1, inputs.get(i), lineIds));
        }
        carryStamps(costLines, built);
        costLines.clear();
        costLines.addAll(built);
    }

    /**
     * A split that is saved again unchanged keeps the person who first typed it, whoever saves the list. One old
     * split is matched to ONE new one: if the same split now appears twice, the second is a new override and is its
     * saver's, and two identical old splits keep their own typists in order. The same rule for a draft's cost lines
     * and for a correction's (where {@code previous} is the revision before).
     */
    public static void carryStamps(List<CostLine> previous, List<CostLine> fresh) {
        List<CostLine> unclaimed = new ArrayList<>(previous.stream().filter(CostLine::isStamped).toList());
        for (CostLine next : fresh) {
            if (next.hasTypedSplit()) {
                for (java.util.Iterator<CostLine> it = unclaimed.iterator(); it.hasNext(); ) {
                    CostLine old = it.next();
                    if (old.sameSplitAs(next)) {
                        next.carryStampFrom(old);
                        it.remove();
                        break;
                    }
                }
            }
        }
    }

    /**
     * Records who typed every split in {@code lines} that has not been attributed yet, and when: the same rule as
     * {@link #stampNewlyTyped}, for a list that is not this consignment's own (a correction's).
     */
    public static void stampNewlyTyped(List<CostLine> lines, String actor, Instant at) {
        if (actor == null || actor.isBlank() || at == null) {
            throw new IllegalArgumentException("say who typed the split, and when");
        }
        for (CostLine cost : lines) {
            if (cost.hasTypedSplit() && !cost.isStamped()) {
                cost.stamp(actor, at);
            }
        }
    }

    /**
     * Cost lines checked by exactly the rules of a draft's (the closed list of types, no bank charge, duty only
     * per line, the charges basis, typed splits naming every line and adding up), but DETACHED: nothing about this
     * consignment changes. A correction's costs are built with it.
     *
     * @param lineIds the ids of the lines the costs are shared over
     */
    public List<CostLine> checkedCostLinesForCorrection(List<CostLineInput> inputs, Set<UUID> lineIds) {
        if (inputs.size() > MAX_COST_LINES) {
            throw new IllegalArgumentException("a consignment holds at most " + MAX_COST_LINES + " cost lines");
        }
        List<CostLine> built = new ArrayList<>(inputs.size());
        for (int i = 0; i < inputs.size(); i++) {
            built.add(checkedCostLine(i + 1, inputs.get(i), lineIds));
        }
        return built;
    }

    /**
     * A cost line rebuilt from a recorded revision: detached, with the typist as it was recorded. (Its shares are by
     * line id, as typed.)
     */
    public CostLine rebuiltCostLine(UUID id, int position, CostType type, BigDecimal amount, CostBasis basis, CostTreatment treatment,
                                    LocalDate incurredOn, String note, Map<UUID, BigDecimal> shares, String typedBy, Instant typedAt) {
        CostLine cost = new CostLine(this, position, type, amount, basis, treatment, incurredOn, note, shares);
        cost.restoreId(id);
        if (typedBy != null && typedAt != null) {
            cost.stamp(typedBy, typedAt);
        }
        return cost;
    }

    /**
     * Records who typed every split that has not been attributed yet, and when. Called by whoever saved
     * the cost lines, straight after {@link #replaceCostLines}: a split that is new or changed is theirs,
     * one that was saved again unchanged keeps its first typist (see {@code replaceCostLines}).
     *
     * @param actor the Keycloak subject of the person
     */
    public void stampNewlyTyped(String actor, Instant at) {
        stampNewlyTyped(costLines, actor, at);
    }

    private CostLine checkedCostLine(int position, CostLineInput input, Set<UUID> lineIds) {
        String where = "cost line " + position;
        if (input.costType() == null) {
            throw new IllegalArgumentException(where + " needs a cost type");
        }
        CostType type = input.costType();
        BigDecimal amount = Ngwee.require(input.amount(), where + ": amount");
        if (amount.signum() < 0) {
            throw new IllegalArgumentException(where + ": the amount cannot be negative");
        }
        CostBasis basis = input.basis() != null ? input.basis() : type.defaultBasis();
        if (basis == null) {
            throw new IllegalArgumentException(where + " (" + type + "): say which basis to share it by: "
                    + "UNITS, VALUE, WEIGHT or DIRECT");
        }
        if (type.isCustoms() && basis != CostBasis.DIRECT) {
            throw new IllegalArgumentException(where + " (" + type + "): duty, surtax and import VAT are typed per line "
                    + "(basis DIRECT), never pooled and spread");
        }
        CostTreatment treatment = input.treatment() != null ? input.treatment() : CostTreatment.CAPITALIZE;
        if (type.isCustoms() && treatment == CostTreatment.PERIOD) {
            throw new IllegalArgumentException(where + " (" + type + "): duty, surtax and import VAT are paid at clearance and reach profit through the cost "
                    + "of the goods, never as a bill for the month: they ride on the goods (CAPITALIZE)");
        }
        String note = checkedText(input.note(), MAX_COST_NOTE, where + ": note");
        if (chargesBasis == ChargesBasis.DUTY_PAID && type.isCustoms() && amount.signum() > 0) {
            throw new IllegalArgumentException(where + " (" + type + "): the charges basis is DUTY_PAID, so the "
                    + "forwarder's price already includes duty; keying it again would count it twice. "
                    + "Key zero, or change the charges basis to FREIGHT_ONLY");
        }
        Map<UUID, BigDecimal> shares = checkedShares(where, input.shares(), amount, treatment, lineIds);
        return new CostLine(this, position, type, amount, basis, treatment, input.incurredOn(), note, shares);
    }

    private Map<UUID, BigDecimal> checkedShares(String where, Map<UUID, BigDecimal> typed, BigDecimal amount,
                                                CostTreatment treatment, Set<UUID> lineIds) {
        if (typed == null || typed.isEmpty()) {
            return Map.of();
        }
        if (treatment == CostTreatment.PERIOD) {
            throw new IllegalArgumentException(where + ": a PERIOD cost is a bill for the month and is not shared "
                    + "between lines, so it cannot carry typed shares");
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (Map.Entry<UUID, BigDecimal> share : typed.entrySet()) {
            if (!lineIds.contains(share.getKey())) {
                throw new IllegalArgumentException(where + ": no such line in this consignment: " + share.getKey());
            }
            BigDecimal value = Ngwee.require(share.getValue(), where + ": a typed share");
            if (value.signum() < 0) {
                throw new IllegalArgumentException(where + ": a typed share cannot be negative");
            }
            sum = sum.add(value);
        }
        if (!typed.keySet().containsAll(lineIds)) {
            throw new IllegalArgumentException(where + ": typed shares must name every line of the consignment "
                    + "(" + lineIds.size() + "), even if some are zero");
        }
        BigDecimal difference = amount.subtract(sum);
        if (difference.signum() != 0) {
            throw new IllegalArgumentException(where + ": the typed shares add up to K" + sum.setScale(2).toPlainString()
                    + " but the cost is K" + amount.toPlainString() + ", out by K"
                    + difference.abs().setScale(2).toPlainString());
        }
        return new LinkedHashMap<>(typed);
    }

    // --------------------------------------------------------------- receiving

    /**
     * The goods have landed. Whether the consignment CAN be received (its costs allocate, its
     * orders are in) is for the service to judge before it gets here; this makes it final.
     */
    public void markReceived(Instant at) {
        requireDraft("receive");
        if (lines.isEmpty()) {
            throw new IllegalStateException("a consignment with no lines cannot be received");
        }
        for (CostLine cost : costLines) {
            if (cost.hasTypedSplit() && !cost.isStamped()) {
                throw new IllegalStateException("cost line " + cost.position() + " has a split nobody is recorded as "
                        + "typing: every override records who typed it, so it cannot be received like that");
            }
        }
        this.status = ConsignmentStatus.RECEIVED;
        this.receivedAt = Objects.requireNonNull(at, "receivedAt");
    }

    // ---------------------------------------------------------------- helpers

    private void requireDraft(String what) {
        if (status != ConsignmentStatus.DRAFT) {
            throw new IllegalStateException("cannot " + what + " a consignment that is " + status);
        }
    }

    private void renumberLines() {
        int position = 0;
        for (ConsignmentLine line : lines) {
            line.renumber(++position);
        }
    }

    private void setOpeningLines(List<OpeningLine> checked) {
        // Cleared and refilled in the same collection: orphanRemoval deletes the old rows.
        lines.clear();
        int position = 0;
        for (OpeningLine typed : checked) {
            lines.add(new ConsignmentLine(this, ++position, typed));
        }
    }

    private static List<OpeningLine> checkedOpeningLines(List<OpeningLine> typed) {
        if (typed == null || typed.isEmpty()) {
            throw new IllegalArgumentException("the opening consignment needs at least one line");
        }
        if (typed.size() > MAX_LINES) {
            throw new IllegalArgumentException("a consignment holds at most " + MAX_LINES + " lines");
        }
        Set<UUID> seen = new HashSet<>();
        List<OpeningLine> checked = new ArrayList<>(typed.size());
        for (int i = 0; i < typed.size(); i++) {
            OpeningLine line = typed.get(i);
            String where = "line " + (i + 1);
            if (line.variantId() == null) {
                throw new IllegalArgumentException(where + " needs a variant");
            }
            if (!seen.add(line.variantId())) {
                throw new IllegalArgumentException(where + ": the same variant appears more than once; "
                        + "the opening stock has one line per item");
            }
            if (line.quantity() < 1) {
                throw new IllegalArgumentException(where + ": quantity must be at least 1");
            }
            Ngwee.require(line.goodsTotal(), where + ": total cost");
            if (line.goodsTotal().signum() <= 0) {
                throw new IllegalArgumentException(where + ": total cost must be above zero: the opening stock's cost "
                        + "is approximated from the old book, and a K0 cost is refused");
            }
            if (line.chargeableWeightGrams() != null && line.chargeableWeightGrams() < 0) {
                throw new IllegalArgumentException(where + ": chargeable weight cannot be negative");
            }
            ConsignmentLine.tidyCountry(line.originCountry());
            ConsignmentLine.tidyHsCode(line.hsCode());
            if (line.sku() == null || line.sku().isBlank() || line.variantTitle() == null || line.variantTitle().isBlank()) {
                throw new IllegalArgumentException(where + " needs the catalogue's sku and name for the variant");
            }
            if (!"SELLABLE".equals(line.kind()) && !"PACKAGING".equals(line.kind())) {
                throw new IllegalArgumentException(where + ": kind must be SELLABLE or PACKAGING");
            }
            checked.add(line);
        }
        return checked;
    }

    /** Blank is none; NUL is refused; trimmed; at most {@code max} characters. */
    private static String checkedText(String text, int max, String what) {
        if (text == null || text.isBlank()) {
            return null;
        }
        if (text.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(what + " must not contain the NUL character");
        }
        String trimmed = text.strip();
        if (trimmed.length() > max) {
            throw new IllegalArgumentException(what + " must be at most " + max + " characters");
        }
        return trimmed;
    }
}

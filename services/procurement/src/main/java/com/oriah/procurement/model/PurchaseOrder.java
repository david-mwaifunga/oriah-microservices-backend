package com.oriah.procurement.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
 * What Oriah placed with ONE supplier: one line per item, each with how many and the
 * all-in total that left the bank (CLIENT_DECISIONS 4.1, screen 2).
 *
 * <h2>Where an order sits in the flow</h2>
 *
 * Orders are placed with one supplier each, weeks apart; a forwarder consolidates
 * several into one shipment under one courier bill. That shipment is a
 * {@code Consignment}, which groups WHOLE orders: there are no part-shipments and no
 * short or extra deliveries (client Q4-Q6), so an order is received in full or not at
 * all. An order belongs to at most one consignment.
 *
 * <h2>Rules held here</h2>
 * <ul>
 *   <li>one to 100 lines, numbered 1.., each with a variant that appears once, a
 *       quantity of at least one, and a total paid above zero in whole ngwee;</li>
 *   <li>an order is changed or cancelled only while OPEN and not attached to a
 *       consignment: attaching FREEZES it, because the consignment's lines are copied
 *       from it. To change it, take it out of the consignment first;</li>
 *   <li>RECEIVED and CANCELLED are the end: nothing changes the order again (the
 *       database refuses it too).</li>
 * </ul>
 * A refused input is an {@code IllegalArgumentException} (400); a refused state an
 * {@code IllegalStateException} (409). The aggregate is untouched either way.
 *
 * <p>Whether the SUPPLIER may take a new order (active) and whether the variants exist
 * are not this class's to know: the service checks them, under the supplier's row lock.
 */
@Entity
@Table(name = "purchase_order")
public class PurchaseOrder extends BaseEntity {

    public static final int MAX_LINES = 100;
    public static final int MAX_NOTE = 500;

    /**
     * One line as it is asked for: the names are what the catalogue called the variant at
     * this moment, kept so history does not change when a product is renamed.
     */
    public record NewLine(UUID variantId, int quantity, BigDecimal totalPaid,
                          String sku, String productTitle, String variantTitle, String kind) {
    }

    @Id
    private UUID id;

    /** The number a person quotes on the phone: PO-000123. Assigned by the database. */
    @Generated(event = EventType.INSERT)
    @Column(name = "ref_no", insertable = false, updatable = false)
    private Long refNo;

    @Column(name = "supplier_id", nullable = false, updatable = false)
    private UUID supplierId;

    @Column(name = "placed_on", nullable = false)
    private LocalDate placedOn;

    @Column(length = MAX_NOTE)
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PurchaseOrderStatus status;

    @Column(name = "consignment_id")
    private UUID consignmentId;

    /** A wrapper type, null until saved: how Spring Data sees an order whose id we assigned as new. */
    @Version
    @Column(nullable = false)
    private Long version;

    @OneToMany(mappedBy = "purchaseOrder", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position asc")
    private List<PurchaseOrderLine> lines = new ArrayList<>();

    protected PurchaseOrder() {
        // JPA
    }

    /**
     * @param placedOn null means today
     * @param today    the date to judge "not in the future" against (passed in so the
     *                 aggregate reads no clock)
     */
    public PurchaseOrder(UUID supplierId, LocalDate placedOn, String note, List<NewLine> lines, LocalDate today) {
        if (supplierId == null) {
            throw new IllegalArgumentException("a purchase order needs a supplier");
        }
        LocalDate date = checkedDate(placedOn, today);
        String checkedNote = checkedNote(note);
        List<NewLine> checked = checkedLines(lines);

        this.id = UUID.randomUUID();
        this.supplierId = supplierId;
        this.placedOn = date;
        this.note = checkedNote;
        this.status = PurchaseOrderStatus.OPEN;
        setLines(checked);
    }

    // ------------------------------------------------------------------ reads

    public UUID id() {
        return id;
    }

    public Long refNo() {
        return refNo;
    }

    public UUID supplierId() {
        return supplierId;
    }

    public LocalDate placedOn() {
        return placedOn;
    }

    public String note() {
        return note;
    }

    public PurchaseOrderStatus status() {
        return status;
    }

    /** The consignment this order is part of, or null. */
    public UUID consignmentId() {
        return consignmentId;
    }

    public boolean isAttached() {
        return consignmentId != null;
    }

    public Long version() {
        return version;
    }

    public List<PurchaseOrderLine> lines() {
        return List.copyOf(lines);
    }

    /** Everything this order cost, all lines together. */
    public BigDecimal totalPaid() {
        return lines.stream().map(PurchaseOrderLine::totalPaid).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // ---------------------------------------------------------------- changes

    /** Replaces every line. Refused unless OPEN and not attached; a refused replacement changes nothing. */
    public void replaceLines(List<NewLine> newLines) {
        requireEditable("change the lines of");
        List<NewLine> checked = checkedLines(newLines);
        setLines(checked);
    }

    /** @return whether anything about the date or the note actually changed */
    public boolean changeHeader(LocalDate newPlacedOn, String newNote, LocalDate today) {
        requireEditable("change");
        LocalDate date = checkedDate(newPlacedOn, today);
        String checkedNote = checkedNote(newNote);
        boolean changed = !date.equals(this.placedOn) || !java.util.Objects.equals(checkedNote, this.note);
        this.placedOn = date;
        this.note = checkedNote;
        return changed;
    }

    public void cancel() {
        requireEditable("cancel");
        this.status = PurchaseOrderStatus.CANCELLED;
    }

    /** Puts this order into a DRAFT consignment. From now on it cannot be edited or cancelled. */
    public void attachTo(UUID consignment) {
        requireOpen("attach");
        if (consignmentId != null) {
            throw new IllegalStateException("purchase order is already in a consignment");
        }
        this.consignmentId = consignment;
    }

    /** Takes it back out of a DRAFT consignment, free to be edited, cancelled or attached elsewhere. */
    public void detach() {
        requireOpen("detach");
        if (consignmentId == null) {
            throw new IllegalStateException("purchase order is not in a consignment");
        }
        this.consignmentId = null;
    }

    /** The consignment was received: this order is done. */
    public void markReceived() {
        requireOpen("receive");
        if (consignmentId == null) {
            throw new IllegalStateException("a purchase order is received through its consignment");
        }
        this.status = PurchaseOrderStatus.RECEIVED;
    }

    // ---------------------------------------------------------------- helpers

    private void requireOpen(String what) {
        if (status != PurchaseOrderStatus.OPEN) {
            throw new IllegalStateException("cannot " + what + " a purchase order that is " + status);
        }
    }

    private void requireEditable(String what) {
        requireOpen(what);
        if (consignmentId != null) {
            throw new IllegalStateException("cannot " + what
                    + " a purchase order that is in a consignment: take it out of the consignment first");
        }
    }

    private static LocalDate checkedDate(LocalDate placedOn, LocalDate today) {
        LocalDate date = placedOn == null ? today : placedOn;
        if (date.isAfter(today)) {
            throw new IllegalArgumentException("a purchase order cannot be dated in the future");
        }
        return date;
    }

    private static String checkedNote(String note) {
        if (note == null || note.isBlank()) {
            return null;
        }
        if (note.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("note must not contain the NUL character");
        }
        String trimmed = note.strip();
        if (trimmed.length() > MAX_NOTE) {
            throw new IllegalArgumentException("note must be at most " + MAX_NOTE + " characters");
        }
        return trimmed;
    }

    private static List<NewLine> checkedLines(List<NewLine> lines) {
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("a purchase order needs at least one line");
        }
        if (lines.size() > MAX_LINES) {
            throw new IllegalArgumentException("a purchase order holds at most " + MAX_LINES + " lines");
        }
        Set<UUID> seen = new HashSet<>();
        List<NewLine> checked = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            NewLine line = lines.get(i);
            String where = "line " + (i + 1);
            if (line.variantId() == null) {
                throw new IllegalArgumentException(where + " needs a variant");
            }
            if (!seen.add(line.variantId())) {
                throw new IllegalArgumentException(where + ": the same variant appears more than once; "
                        + "an order has one line per item");
            }
            if (line.quantity() < 1) {
                throw new IllegalArgumentException(where + ": quantity must be at least 1");
            }
            Ngwee.require(line.totalPaid(), where + ": total paid");
            if (line.totalPaid().signum() <= 0) {
                throw new IllegalArgumentException(where + ": total paid must be above zero");
            }
            if (line.sku() == null || line.sku().isBlank() || line.variantTitle() == null || line.variantTitle().isBlank()) {
                throw new IllegalArgumentException(where + " needs the catalogue's sku and name for the variant");
            }
            if (!"SELLABLE".equals(line.kind()) && !"PACKAGING".equals(line.kind())) {
                throw new IllegalArgumentException(where + ": kind must be SELLABLE or PACKAGING");
            }
            checked.add(new NewLine(line.variantId(), line.quantity(), line.totalPaid().setScale(2),
                    line.sku(), line.productTitle(), line.variantTitle(), line.kind()));
        }
        return checked;
    }

    private void setLines(List<NewLine> checked) {
        // Cleared and refilled in the same collection: orphanRemoval deletes the old rows.
        this.lines.clear();
        int position = 1;
        for (NewLine line : checked) {
            this.lines.add(new PurchaseOrderLine(this, position++, line));
        }
    }
}

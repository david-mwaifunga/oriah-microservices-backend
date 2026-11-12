package com.oriah.procurement.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.oriah.jpa.BaseEntity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.Table;

/**
 * One cost shared across a consignment: what the courier charged, or a duty.
 *
 * <p>{@link #basis()} says how it is shared between the lines, {@link #treatment()} whether it
 * rides on the goods into the unit costs ({@code CAPITALIZE}) or is a bill for the month
 * ({@code PERIOD}). When a person typed the split, {@link #shares()} holds it, per line.
 *
 * <p>Created and replaced only through its {@link Consignment}.
 */
@Entity
@Table(name = "cost_line")
public class CostLine extends BaseEntity {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "consignment_id", nullable = false, updatable = false)
    private Consignment consignment;

    @Column(nullable = false)
    private int position;

    @Enumerated(EnumType.STRING)
    @Column(name = "cost_type", nullable = false, length = 20, updatable = false)
    private CostType costType;

    @Column(nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, updatable = false)
    private CostBasis basis;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, updatable = false)
    private CostTreatment treatment;

    @Column(name = "incurred_on", updatable = false)
    private LocalDate incurredOn;

    @Column(length = 200, updatable = false)
    private String note;

    /** consignment line id to the amount typed for it. Empty unless a person typed the split. */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "cost_line_share", joinColumns = @JoinColumn(name = "cost_line_id"))
    @MapKeyColumn(name = "consignment_line_id")
    @Column(name = "amount", nullable = false, precision = 12, scale = 2)
    private Map<UUID, BigDecimal> shares = new LinkedHashMap<>();

    protected CostLine() {
        // JPA
    }

    /** Already checked by the consignment. */
    CostLine(Consignment consignment, int position, CostType costType, BigDecimal amount, CostBasis basis,
             CostTreatment treatment, LocalDate incurredOn, String note, Map<UUID, BigDecimal> typedShares) {
        this.id = UUID.randomUUID();
        this.consignment = consignment;
        this.position = position;
        this.costType = costType;
        this.amount = amount.setScale(2);
        this.basis = basis;
        this.treatment = treatment;
        this.incurredOn = incurredOn;
        this.note = note;
        if (typedShares != null) {
            typedShares.forEach((line, share) -> this.shares.put(line, share.setScale(2)));
        }
    }

    public UUID id() {
        return id;
    }

    public int position() {
        return position;
    }

    public CostType costType() {
        return costType;
    }

    public BigDecimal amount() {
        return amount;
    }

    public CostBasis basis() {
        return basis;
    }

    public CostTreatment treatment() {
        return treatment;
    }

    public LocalDate incurredOn() {
        return incurredOn;
    }

    public String note() {
        return note;
    }

    /** What a person typed for each line, or empty. A copy. */
    public Map<UUID, BigDecimal> shares() {
        return Map.copyOf(shares);
    }

    // ---------------------------------------------------------------- changes

    void renumber(int newPosition) {
        this.position = newPosition;
    }

    /** A consignment line left the consignment: what was typed for it goes too. */
    void dropSharesOf(Set<UUID> removedLines) {
        shares.keySet().removeAll(removedLines);
    }

    /** The lines were replaced wholesale: no typed share can still point at one. */
    void clearShares() {
        shares.clear();
    }
}

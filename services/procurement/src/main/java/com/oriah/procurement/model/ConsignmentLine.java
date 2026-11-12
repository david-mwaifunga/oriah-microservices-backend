package com.oriah.procurement.model;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import com.oriah.jpa.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * One line of a consignment: a variant, how many, and the all-in total paid for them.
 *
 * <p>On a normal consignment a line is a COPY of one purchase order line, made when the order
 * is attached (the order is frozen from then on, so the copy cannot drift), and it remembers
 * which supplier it was bought from: the same variant bought from two suppliers is two lines,
 * because cost belongs to a receipt, never to the product. On the opening consignment a line
 * is typed in, with no supplier and no order.
 *
 * <p>Created and replaced only through its {@link Consignment}, which owns the rules.
 */
@Entity
@Table(name = "consignment_line")
public class ConsignmentLine extends BaseEntity {

    private static final Pattern COUNTRY = Pattern.compile("[A-Z]{2}");
    private static final Pattern HS_CODE = Pattern.compile("[0-9.]{4,14}");

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "consignment_id", nullable = false, updatable = false)
    private Consignment consignment;

    /** Held equal to the consignment's own flag by a composite foreign key. */
    @Column(name = "is_opening", nullable = false, updatable = false)
    private boolean opening;

    @Column(nullable = false)
    private int position;

    @Column(name = "supplier_id", updatable = false)
    private UUID supplierId;

    @Column(name = "purchase_order_id", updatable = false)
    private UUID purchaseOrderId;

    @Column(name = "purchase_order_line_id", updatable = false)
    private UUID purchaseOrderLineId;

    @Column(name = "variant_id", nullable = false, updatable = false)
    private UUID variantId;

    @Column(nullable = false, length = 64, updatable = false)
    private String sku;

    @Column(name = "product_title", length = 200, updatable = false)
    private String productTitle;

    @Column(name = "variant_title", nullable = false, length = 300, updatable = false)
    private String variantTitle;

    @Column(nullable = false, length = 10, updatable = false)
    private String kind;

    @Column(nullable = false, updatable = false)
    private int quantity;

    @Column(name = "goods_total", nullable = false, precision = 12, scale = 2, updatable = false)
    private BigDecimal goodsTotal;

    @Column(name = "chargeable_weight_grams")
    private Long chargeableWeightGrams;

    @Column(name = "origin_country", length = 2)
    private String originCountry;

    @Column(name = "hs_code", length = 14)
    private String hsCode;

    protected ConsignmentLine() {
        // JPA
    }

    /** A line copied from a purchase order line. */
    ConsignmentLine(Consignment consignment, int position, UUID supplierId, UUID purchaseOrderId, PurchaseOrderLine source) {
        this.id = UUID.randomUUID();
        this.consignment = consignment;
        this.opening = false;
        this.position = position;
        this.supplierId = supplierId;
        this.purchaseOrderId = purchaseOrderId;
        this.purchaseOrderLineId = source.id();
        this.variantId = source.variantId();
        this.sku = source.sku();
        this.productTitle = source.productTitle();
        this.variantTitle = source.variantTitle();
        this.kind = source.kind();
        this.quantity = source.quantity();
        this.goodsTotal = source.totalPaid();
    }

    /** A line typed in on the opening consignment (already checked by the consignment). */
    ConsignmentLine(Consignment consignment, int position, Consignment.OpeningLine typed) {
        this.id = UUID.randomUUID();
        this.consignment = consignment;
        this.opening = true;
        this.position = position;
        this.variantId = typed.variantId();
        this.sku = typed.sku();
        this.productTitle = typed.productTitle();
        this.variantTitle = typed.variantTitle();
        this.kind = typed.kind();
        this.quantity = typed.quantity();
        this.goodsTotal = typed.goodsTotal().setScale(2);
        this.chargeableWeightGrams = typed.chargeableWeightGrams();
        this.originCountry = tidyCountry(typed.originCountry());
        this.hsCode = tidyHsCode(typed.hsCode());
    }

    public UUID id() {
        return id;
    }

    public boolean isOpening() {
        return opening;
    }

    public int position() {
        return position;
    }

    /** Null on an opening line: "opening stock, supplier unknown". */
    public UUID supplierId() {
        return supplierId;
    }

    public UUID purchaseOrderId() {
        return purchaseOrderId;
    }

    public UUID purchaseOrderLineId() {
        return purchaseOrderLineId;
    }

    public UUID variantId() {
        return variantId;
    }

    public String sku() {
        return sku;
    }

    public String productTitle() {
        return productTitle;
    }

    public String variantTitle() {
        return variantTitle;
    }

    /** SELLABLE or PACKAGING. */
    public String kind() {
        return kind;
    }

    public int quantity() {
        return quantity;
    }

    /** All-in, as the bank app showed it, for the whole line. */
    public BigDecimal goodsTotal() {
        return goodsTotal;
    }

    /** What the courier billed on for the whole line, in whole grams; null until keyed. */
    public Long chargeableWeightGrams() {
        return chargeableWeightGrams;
    }

    public String originCountry() {
        return originCountry;
    }

    public String hsCode() {
        return hsCode;
    }

    // ---------------------------------------------------------------- changes

    void renumber(int newPosition) {
        this.position = newPosition;
    }

    void changeDetails(Long weightGrams, String origin, String hs) {
        if (weightGrams != null && weightGrams < 0) {
            throw new IllegalArgumentException("chargeable weight cannot be negative");
        }
        String country = tidyCountry(origin);
        String code = tidyHsCode(hs);
        this.chargeableWeightGrams = weightGrams;
        this.originCountry = country;
        this.hsCode = code;
    }

    // ---------------------------------------------------------------- tidying

    /** Blank is none; otherwise two letters, upper-cased. */
    static String tidyCountry(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String country = value.strip().toUpperCase(Locale.ROOT);
        if (!COUNTRY.matcher(country).matches()) {
            throw new IllegalArgumentException("origin country must be a two-letter country code, such as CN or AE");
        }
        return country;
    }

    /** Blank is none; otherwise digits and dots, 4 to 14 of them (7113 or 7113.19.00). */
    static String tidyHsCode(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String code = value.strip();
        if (!HS_CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("HS code must be digits and dots, such as 7113.19.00");
        }
        return code;
    }
}

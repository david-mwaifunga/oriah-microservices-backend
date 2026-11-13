package com.oriah.inventory.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A consignment line as it landed (or as a correction restated it): the payload of {@code consignment.received}
 * and, with the last three fields filled, of {@code consignment.amended}. A full snapshot, so inventory needs
 * nothing else to make stock from it.
 *
 * <p>Read leniently (unknown fields are ignored, an absent {@code linePosition} is null: events written before it
 * existed have none) and then {@link #validate() checked strictly}: every promise the event makes is verified
 * before any stock exists, because stock made from a wrong event is a wrong cost for the life of the lot. An event
 * that fails is refused, never repaired.
 *
 * <p>{@code receivedAt} and {@code amendedAt} are read as text and parsed with {@link Instant#parse}, so reading
 * does not depend on which JSON mapper is injected.
 *
 * @param slices       the unit costs, dearer first: one or two, exactly a ngwee apart when two
 * @param linePosition where the line sits in its consignment (1-based); breaks a tie between lots of one receipt
 * @param revision     an amendment's revision (2 and up); null for a receipt
 * @param reason       why it was amended; null for a receipt
 * @param amendedAt    when it was amended; null for a receipt
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReceiptSnapshot(
        UUID consignmentId,
        String consignmentReference,
        UUID consignmentLineId,
        UUID variantId,
        String sku,
        String productTitle,
        String variantTitle,
        String kind,
        UUID supplierId,
        String supplierName,
        String receivedAt,
        boolean isEstimated,
        String currency,
        int quantity,
        BigDecimal goodsTotal,
        BigDecimal sharedCost,
        BigDecimal landedTotal,
        List<Slice> slices,
        Integer linePosition,
        Integer revision,
        String reason,
        String amendedAt) {

    private static final Set<String> KINDS = Set.of("SELLABLE", "PACKAGING");
    private static final BigDecimal A_NGWEE = new BigDecimal("0.01");

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Slice(int quantity, BigDecimal unitCost) {
    }

    public Instant receivedAtInstant() {
        return instant("receivedAt", receivedAt);
    }

    public Instant amendedAtInstant() {
        return amendedAt == null ? null : instant("amendedAt", amendedAt);
    }

    /** @throws IllegalArgumentException naming the first promise that is broken */
    public void validate() {
        require(consignmentId, "consignmentId");
        require(consignmentLineId, "consignmentLineId");
        require(variantId, "variantId");
        require(sku, "sku");
        require(variantTitle, "variantTitle");
        if (quantity < 1) {
            throw new IllegalArgumentException("quantity must be at least 1, was " + quantity);
        }
        if (kind == null || !KINDS.contains(kind)) {
            throw new IllegalArgumentException("kind must be SELLABLE or PACKAGING, was " + kind);
        }
        if (!"ZMW".equals(currency)) {
            throw new IllegalArgumentException("currency must be ZMW, was " + currency);
        }
        if (linePosition != null && linePosition < 1) {
            throw new IllegalArgumentException("linePosition must be at least 1, was " + linePosition);
        }
        receivedAtInstant();
        amendedAtInstant();

        money(goodsTotal, "goodsTotal");
        money(sharedCost, "sharedCost");
        money(landedTotal, "landedTotal");
        if (slices == null || slices.isEmpty() || slices.size() > 2) {
            throw new IllegalArgumentException("slices: a line lands at one or two unit costs, got "
                    + (slices == null ? 0 : slices.size()));
        }
        int units = 0;
        BigDecimal total = BigDecimal.ZERO;
        for (Slice slice : slices) {
            if (slice == null || slice.quantity() < 1) {
                throw new IllegalArgumentException("slices: every slice needs at least one unit");
            }
            money(slice.unitCost(), "slice unitCost");
            units += slice.quantity();
            total = total.add(slice.unitCost().multiply(BigDecimal.valueOf(slice.quantity())));
        }
        if (units != quantity) {
            throw new IllegalArgumentException("slices add up to " + units + " units, not the line's " + quantity);
        }
        if (slices.size() == 2) {
            BigDecimal first = slices.get(0).unitCost();
            BigDecimal second = slices.get(1).unitCost();
            if (first.compareTo(second) < 0) {
                throw new IllegalArgumentException("the dearer unit cost must come first");
            }
            if (first.subtract(second).compareTo(A_NGWEE) != 0) {
                throw new IllegalArgumentException("the two unit costs must be exactly a ngwee apart, were "
                        + first + " and " + second);
            }
        }
        if (goodsTotal.add(sharedCost).compareTo(landedTotal) != 0) {
            throw new IllegalArgumentException("goods " + goodsTotal + " plus shared cost " + sharedCost
                    + " is not the landed total " + landedTotal);
        }
        if (total.compareTo(landedTotal) != 0) {
            throw new IllegalArgumentException("the slices come to " + total + ", not the landed total " + landedTotal);
        }
    }

    /**
     * What an amendment promises on top of what a receipt does: a revision of 2 or more, why it was made, and when.
     * The line's quantity, its variant and its position do not change in an amendment; that is checked against what
     * inventory holds.
     */
    public void validateAmendment() {
        validate();
        if (revision == null || revision < 2) {
            throw new IllegalArgumentException("an amendment has a revision of 2 or more, was " + revision);
        }
        if (reason == null || reason.isBlank() || reason.length() > 200) {
            throw new IllegalArgumentException("an amendment says why it was made, in 1 to 200 characters");
        }
        if (amendedAt == null) {
            throw new IllegalArgumentException("amendedAt is missing");
        }
    }

    private static Instant instant(String field, String value) {
        if (value == null) {
            throw new IllegalArgumentException(field + " is missing");
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(field + " is not a timestamp: " + value);
        }
    }

    private static void require(Object value, String field) {
        if (value == null || (value instanceof String s && s.isBlank())) {
            throw new IllegalArgumentException(field + " is missing");
        }
    }

    private static void money(BigDecimal value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " is missing");
        }
        if (value.scale() > 2) {
            throw new IllegalArgumentException(field + " has more than two decimals: " + value.toPlainString());
        }
        if (value.signum() < 0) {
            throw new IllegalArgumentException(field + " is negative: " + value.toPlainString());
        }
    }
}

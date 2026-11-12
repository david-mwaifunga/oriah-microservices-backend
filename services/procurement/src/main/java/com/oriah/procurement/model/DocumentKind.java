package com.oriah.procurement.model;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * What a stored document is for. The supplier's invoice supports a purchase order (and a
 * consignment, when one invoice covers several orders); the courier's invoice and a ZRA
 * assessment support a consignment, because they bill the shipment, not an order.
 */
public enum DocumentKind {

    SUPPLIER_INVOICE(true),
    COURIER_INVOICE(false),
    ZRA_ASSESSMENT(false),
    OTHER(true);

    private final boolean fitsAnOrder;

    DocumentKind(boolean fitsAnOrder) {
        this.fitsAnOrder = fitsAnOrder;
    }

    /** May a document of this kind be attached to a purchase order (as well as to a consignment)? */
    public boolean fitsAnOrder() {
        return fitsAnOrder;
    }

    /**
     * Reads a kind by name, ignoring letter case, spaces and hyphens.
     *
     * @throws IllegalArgumentException if it is missing, or not one of the kinds (which are listed)
     */
    public static DocumentKind parse(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a document needs a kind; choose one of " + list());
        }
        String normal = name.strip().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        return Arrays.stream(values()).filter(k -> k.name().equals(normal)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("'" + name.strip() + "' is not a document kind; choose one of "
                        + list()));
    }

    private static String list() {
        return Arrays.stream(values()).map(Enum::name).collect(Collectors.joining(", "));
    }
}

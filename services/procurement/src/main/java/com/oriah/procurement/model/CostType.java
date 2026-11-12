package com.oriah.procurement.model;

import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The closed list of costs that can be put on a consignment, each with the basis it normally
 * shares by (CLIENT_DECISIONS 3). The basis is data on each cost line, so a default is a
 * convenience, not a rule; the one thing that is a rule is that duty, surtax and import VAT
 * are typed per line ({@link CostBasis#DIRECT}) from the assessment, never pooled and spread.
 *
 * <p>What is NOT here, on purpose: a bank or transfer charge. The price on a purchase order
 * already is the total that left the bank, so a line of its own would count it twice.
 * {@link #parse} refuses it by name and says why.
 */
public enum CostType {

    /** The courier or forwarder's single charge: the real inbound cost. */
    COURIER_INBOUND(CostBasis.UNITS, false),
    FREIGHT(CostBasis.UNITS, false),
    /** No default: a person says how it should be shared. */
    INSURANCE(null, false),
    /** No default: a person says how it should be shared. */
    PORT_INCIDENTALS(null, false),
    INBOUND_TRANSPORT(CostBasis.UNITS, false),
    /** A flat agent fee follows the value of the goods. */
    AGENT_FEE(CostBasis.VALUE, false),
    DUTY(CostBasis.DIRECT, true),
    SURTAX(CostBasis.DIRECT, true),
    IMPORT_VAT(CostBasis.DIRECT, true);

    private static final Pattern BANK_OR_TRANSFER = Pattern.compile("bank|transfer", Pattern.CASE_INSENSITIVE);

    private final CostBasis defaultBasis;
    private final boolean customs;

    CostType(CostBasis defaultBasis, boolean customs) {
        this.defaultBasis = defaultBasis;
        this.customs = customs;
    }

    /** The basis this cost is normally shared by, or null when a person must choose. */
    public CostBasis defaultBasis() {
        return defaultBasis;
    }

    /** Duty, surtax and import VAT: assessed by ZRA, keyed per line, zero today. */
    public boolean isCustoms() {
        return customs;
    }

    /**
     * Reads a type by name, ignoring letter case, spaces and hyphens.
     *
     * @throws IllegalArgumentException for a bank or transfer charge (with the reason), and for
     *         anything else that is not on the list (which is shown)
     */
    public static CostType parse(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a cost line needs a cost type; choose one of " + list());
        }
        if (BANK_OR_TRANSFER.matcher(name).find()) {
            throw new IllegalArgumentException("A bank or transfer charge is not a separate cost: the supplier price "
                    + "on the purchase order already is the total that left the bank, so keying one would count it twice.");
        }
        String normal = name.strip().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
        for (CostType type : values()) {
            if (type.name().equals(normal)) {
                return type;
            }
        }
        throw new IllegalArgumentException("'" + name.strip() + "' is not a cost type; choose one of " + list());
    }

    private static String list() {
        return Arrays.stream(values()).map(Enum::name).collect(Collectors.joining(", "));
    }
}

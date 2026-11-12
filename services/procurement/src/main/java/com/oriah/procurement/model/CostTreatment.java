package com.oriah.procurement.model;

/**
 * What happens to a cost line's money.
 *
 * <p>{@link #CAPITALIZE}: it rides on the goods, goes into the unit costs, and
 * reaches profit when the piece sells. {@link #PERIOD}: it is a bill for the
 * month, shown on the consignment but left out of the unit costs. The split is
 * data on each cost line, not a rule in the code.
 */
public enum CostTreatment {
    CAPITALIZE,
    PERIOD
}

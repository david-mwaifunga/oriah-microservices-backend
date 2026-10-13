package com.oriah.order.exception;

import java.util.List;
import java.util.UUID;

/**
 * One or more lines are not something catalog will sell.
 *
 * <h2>Why the variant ids are named</h2>
 *
 * checkout answered this exact question already, and shipped it: its own
 * {@code BagNotPurchasableException} names the offending ids, and keeps mixed
 * currency as a separate type "because the remedy differs". The ids are the
 * caller's own input, so naming them reveals nothing they did not send -- and
 * a refusal that says only "something in your order cannot be bought" leaves a
 * shopper with no way to fix it.
 *
 * <p>What is NOT distinguished is WHY a line is unsellable: withdrawn,
 * unpublished, priced at zero, absent from catalog's answer, or carrying no
 * title are all one refusal. Separating them would turn the response into a
 * probe for catalogue state, and checkout does not separate them either on
 * this authenticated path.
 */
public class BagNotPurchasableException extends IllegalStateException {

    private final List<UUID> variantIds;

    public BagNotPurchasableException(List<UUID> variantIds) {
        super("These items are not available for purchase right now: " + variantIds);
        this.variantIds = List.copyOf(variantIds);
    }

    public List<UUID> variantIds() {
        return variantIds;
    }
}

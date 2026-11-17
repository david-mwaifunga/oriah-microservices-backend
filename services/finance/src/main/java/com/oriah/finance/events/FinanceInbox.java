package com.oriah.finance.events;

import java.util.Set;

import com.oriah.events.InboxConsumer;

/**
 * Finance as a consumer of the event log: ONE declaration, {@code finance}, covers every topic it reads (order, payment, inventory, procurement and catalog) and the replay
 * topic its catch-up is answered on. Everything every producer has ever written is declared here, handled or ignored, so a replay of any producer's whole log never stalls on
 * a type finance has no use for; a type nobody declared is refused on its first delivery, so a new one is noticed. A producer that adds a type declares it here first.
 *
 * <p>What is a FACT (applied once, in any order) and what is a SNAPSHOT (a whole product or consignment line, of which only the newest counts, so applied in order): the
 * snapshots are {@link #SNAPSHOTS}.
 */
public final class FinanceInbox {

    /** The aggregate types, spelled as each producer writes them into its outbox. */
    static final String ORDER = "order";
    static final String CONFIRMATION = "payment_confirmation";
    static final String REFUND_DUE = "refund_due";
    static final String MONEY_OUT = "money_out";
    static final String STOCK_ACTION = "stock_action";
    static final String CONSIGNMENT_LINE = "consignment_line";
    static final String CONSIGNMENT = "consignment";
    static final String PRODUCT = "product";

    /** Event types that are a whole snapshot of something that changes: only the newest of each aggregate is applied. */
    static final Set<String> SNAPSHOTS = Set.of("consignment.received", "consignment.amended", "product.created", "product.updated", "product.deleted");

    public static final InboxConsumer FINANCE = InboxConsumer.named("finance")
            // order: the sale (both versions: version 1 names no confirmation and is set aside), the parcel's dispatch and the fare, and that an order exists
            .handles(ORDER, "order.paid", 1, 2)
            .handles(ORDER, "order.shipped", 1)
            .handles(ORDER, "order.created", 1)
            .ignores("order.cancelled", "order.expired", "order.fulfilled", "order.delivered", "order.payment_submitted", "order.payment_unsettled")
            // order, Phase 12: a paid order cancelled before it left, and pieces that came back
            .handles(ORDER, "order.sale_cancelled", 1)
            .handles(ORDER, "order.return_recorded", 1)
            // payment: money that came in, was taken back, or is owed back; its claims and the card path (switched off until Phase 13) are not money finance books
            .handles(CONFIRMATION, "payment.confirmed", 1)
            .handles(CONFIRMATION, "payment.voided", 1)
            .handles(CONFIRMATION, "payment.held", 1)
            .ignores("payment.rejected", "payment.succeeded", "payment.failed")
            // payment, Phase 12: what is owed for a correction, and a debt closed without a payout
            .handles(REFUND_DUE, "payment.refund_due_recorded", 1)
            .handles(REFUND_DUE, "payment.refund_closed", 1)
            // ... and money that went out (a refund, held money returned), and a payout cancelled
            .handles(MONEY_OUT, "payment.paid_out", 1)
            .handles(MONEY_OUT, "payment.payout_voided", 1)
            // inventory: what every stock movement cost; a hold and a box swap are not costs (the swap's movements arrive as costs)
            .handles(STOCK_ACTION, "stock.cost_recorded", 1)
            .ignores("reservation.consumed", "reservation.expired", "box.swapped")
            // procurement: the lines (for the estimated-stock list) and the bills for the month
            .handles(CONSIGNMENT_LINE, "consignment.received", 1)
            .handles(CONSIGNMENT_LINE, "consignment.amended", 1)
            .handles(CONSIGNMENT, "consignment.period_costs", 1)
            // catalog: product names for the report, and every price saved (audit only: finance reads none)
            .handles(PRODUCT, "product.created", 1)
            .handles(PRODUCT, "product.updated", 1)
            .handles(PRODUCT, "product.deleted", 1)
            .ignores("price.set");

    private FinanceInbox() {
    }
}

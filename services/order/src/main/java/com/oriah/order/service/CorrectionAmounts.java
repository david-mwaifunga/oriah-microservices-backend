package com.oriah.order.service;

import com.oriah.money.Ngwee;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

/**
 * What a customer is owed when a paid sale is undone (Phase 12, plan section 5). A pure rule: no Spring, no database.
 *
 * <ul>
 *   <li><b>Cancelled before dispatch:</b> every line's (list price less its per-piece discount) times its pieces, plus the delivery charge actually paid.
 *       That is always the order's total, which is what was paid; if it is not, something is wrong with the order and nothing is written.</li>
 *   <li><b>Returned after dispatch or hand-over:</b> the returned pieces only: nothing for delivery, nothing for the box.</li>
 * </ul>
 *
 * A promotion is exact per piece (the discount is stored per line), so nothing is split or rounded. A delivery that was waived was never paid, so it is
 * never owed: its amount is only what finance takes back from its "delivery given up" memo.
 */
public final class CorrectionAmounts {

    /** One sale line, or the part of it that is corrected: the pieces, the list price of one and what a promotion took off one. */
    public record Line(UUID lineId, int quantity, BigDecimal unitPrice, BigDecimal discountPerPiece) {
    }

    /** Every amount to the ngwee. {@code owedGross = goodsAtList - discounts + deliveryRefunded}. */
    public record Amounts(BigDecimal goodsAtList, BigDecimal discounts, BigDecimal deliveryRefunded, BigDecimal waiverTakenBack, BigDecimal owedGross) {
    }

    private CorrectionAmounts() {
    }

    /**
     * @param lines                every line of the order, whole
     * @param shippingAmount       the delivery charge on the order (K0.00 for a pickup, a courier the recipient pays, free or waived delivery)
     * @param deliveryWaivedAmount what delivery would have cost had it not been waived; null when it was not
     * @param orderTotal           the order's total, which is what was paid
     * @throws IllegalStateException what is owed is not the order's total
     */
    public static Amounts cancellation(List<Line> lines, BigDecimal shippingAmount, BigDecimal deliveryWaivedAmount, BigDecimal orderTotal) {
        BigDecimal delivery = stored(shippingAmount == null ? BigDecimal.ZERO : shippingAmount);
        BigDecimal waived = stored(deliveryWaivedAmount == null ? BigDecimal.ZERO : deliveryWaivedAmount);
        Amounts goods = ofReturn(lines);
        BigDecimal owed = goods.owedGross().add(delivery);
        if (orderTotal == null || owed.compareTo(orderTotal) != 0) {
            throw new IllegalStateException("a cancelled order owes what was paid: its lines and delivery come to " + owed + " but its total is " + orderTotal);
        }
        return new Amounts(goods.goodsAtList(), goods.discounts(), delivery, waived, owed);
    }

    /** @param lines the pieces that came back, per sale line */
    public static Amounts ofReturn(List<Line> lines) {
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("a correction names at least one line");
        }
        BigDecimal goods = BigDecimal.ZERO;
        BigDecimal discounts = BigDecimal.ZERO;
        for (Line line : lines) {
            check(line);
            BigDecimal pieces = BigDecimal.valueOf(line.quantity());
            goods = goods.add(line.unitPrice().multiply(pieces));
            discounts = discounts.add(line.discountPerPiece().multiply(pieces));
        }
        BigDecimal zero = stored(BigDecimal.ZERO);
        return new Amounts(stored(goods), stored(discounts), zero, zero, stored(goods.subtract(discounts)));
    }

    /** What one line gives back: (list price less its discount) times its pieces. */
    public static BigDecimal lineRefund(Line line) {
        check(line);
        return stored(line.unitPrice().subtract(line.discountPerPiece()).multiply(BigDecimal.valueOf(line.quantity())));
    }

    private static void check(Line line) {
        if (line.unitPrice() == null || line.discountPerPiece() == null) {
            // A sale from before discounts were stored per piece: unknown is never read as zero.
            throw new IllegalArgumentException("line " + line.lineId() + " has no price or no per-piece discount recorded");
        }
        if (line.quantity() < 1 || line.unitPrice().signum() < 0 || line.discountPerPiece().signum() < 0
                || line.discountPerPiece().compareTo(line.unitPrice()) > 0) {
            throw new IllegalArgumentException("line " + line.lineId() + " is not a sale line: " + line.quantity() + " x " + line.unitPrice() + " less "
                    + line.discountPerPiece());
        }
    }

    private static BigDecimal stored(BigDecimal amount) {
        return amount.setScale(Ngwee.DECIMAL_PLACES, RoundingMode.UNNECESSARY);
    }
}

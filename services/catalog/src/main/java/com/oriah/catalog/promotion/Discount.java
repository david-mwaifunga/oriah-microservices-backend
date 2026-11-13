package com.oriah.catalog.promotion;

import java.math.BigDecimal;
import java.util.UUID;

/** The discount a piece gets: the promotion that gives it and the Kwacha taken off EACH piece (never more than the piece's price). */
public record Discount(UUID promotionId, BigDecimal perPiece) {
}

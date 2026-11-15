package com.oriah.order.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * What staff record about a sale taken by phone, chat or paper (Phase 9), beyond what any order has: who recorded it (from the token), the caller, the kind of
 * sale and the date it happened, a slip number, the hash of the request (so a repeat is recognised) and, for a delivery agreed by phone, the amount and the
 * reason it differs from the shop's quote. {@link Order#recorded} checks every part.
 *
 * @param recordedBy     {@code user:<keycloak subject>} of the member of staff
 * @param callerName     who is buying (always)
 * @param callerPhone    +260 and digits; may be missing only for a pickup
 * @param kind           {@code LIVE_PHONE} or {@code LATE_PAPER}
 * @param occurredAt     when the sale happened (now for a live sale)
 * @param backdateReason why a late paper sale is recorded late (required for it, refused for a live sale)
 * @param slipReference  the paper slip's number, when it has one
 * @param requestHash    64 hex characters: the request this order was made from
 * @param agreedAmount   the delivery charge agreed by phone, or null when the delivery is the shop's published price
 * @param agreedReason   why the agreed amount differs from the shop's quote, or null
 */
public record RecordedSale(String recordedBy, String callerName, String callerPhone, SaleKind kind, Instant occurredAt, String backdateReason, String slipReference,
                           String requestHash, BigDecimal agreedAmount, String agreedReason) {
}

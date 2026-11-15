package com.oriah.order.service;

import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.order.dto.RecordOrderRequest;
import com.oriah.order.exception.SalesProblem;
import com.oriah.order.model.Channel;
import com.oriah.order.model.Order;
import com.oriah.order.model.RecordedSale;
import com.oriah.order.repository.OrderRepository;
import com.oriah.quote.QuoteDigest;

import lombok.extern.slf4j.Slf4j;

/**
 * The database halves of recording a sale (Phase 9): each one short local transaction, with the outbound calls (catalog, shipping, inventory) made outside
 * them by {@link RecordedOrderService}, exactly as {@link OrderWriter} does for a shopper's order.
 *
 * <h2>Once, whoever records and however often</h2>
 *
 * An Idempotency-Key is used once among recorded orders, whoever recorded them (V13's unique index). The lookup runs twice: a short precheck BEFORE any date
 * rule, price or outbound call (a repeat after a lost reply is answered as it was, whatever has moved since, and costs nothing), and again here under one
 * advisory lock, because two requests with the same key can both pass the first look while pricing. Only a request that is still new is written.
 */
@Service
@Slf4j
public class RecordedOrderWriter {

    private final OrderRepository repository;
    private final JdbcTemplate jdbc;

    public RecordedOrderWriter(OrderRepository repository, JdbcTemplate jdbc) {
        this.repository = repository;
        this.jdbc = jdbc;
    }

    /** What {@link #begin} did: an order written PENDING to finish placing, or the order the same request made before. */
    public record Begun(Order order, boolean created) {
    }

    /** The order this key made, if any: the precheck, before any date rule, price or outbound call. */
    @Transactional(readOnly = true)
    public Optional<Order> findByKey(String key) {
        return repository.findByChannelAndIdempotencyKey(Channel.PHONE, key).map(RecordedOrderWriter::withItems);
    }

    /** The order with its lines read while the session is open: it is answered after this transaction ends. */
    private static Order withItems(Order order) {
        order.items().size();
        return order;
    }

    /**
     * Writes the sale PENDING (nobody has been told: inventory is asked next), unless this key already made one.
     *
     * @throws SalesProblem 409 {@code idempotency-key-reused} when the key made an order from another request
     */
    @Transactional
    public Begun begin(String key, RecordedSale sale, RecordedPricing.Priced priced, RecordOrderRequest request, OrderWriter.DeliveryDetails delivery) {
        // One lock for all recorded sales, held to the commit (its key is its own name: distinct from a shopper's and from the outbox's). A recorded order is a
        // few a day, so the lock costs nothing; it makes "look, then write" one step.
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended('order-sale:phone', 0))", (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
        });
        Optional<Order> keyed = repository.findByChannelAndIdempotencyKey(Channel.PHONE, key);
        if (keyed.isPresent()) {
            requireSameRequest(keyed.get(), sale.requestHash());
            return new Begun(withItems(keyed.get()), false);
        }
        var d = priced.delivery();
        Order order = Order.recorded(sale, priced.currency(), priced.isPickup() ? com.oriah.quote.Quote.SELF_COLLECT : d.optionCode(), d.amount(),
                priced.isPickup() ? null : d.townId(), priced.isPickup() ? null : d.payer());
        for (int i = 0; i < request.lines().size(); i++) {
            // Lines are kept in the order the person typed them: the priced lines follow the request's, one for one.
            var line = priced.lines().get(i);
            var item = order.addItem(line.variantId(), line.productId(), line.title(), line.quantity(), line.unitPrice(), line.discountPerPiece(), line.promotionId());
            if (request.lines().get(i).boxVariantId() != null) {
                item.requestBox(request.lines().get(i).boxVariantId());
            }
        }
        if (delivery != null && !priced.isPickup()) {
            order.recordDelivery(delivery.recipientName(), delivery.recipientPhone(), delivery.line1(), delivery.line2(), delivery.city(), delivery.state(),
                    delivery.postalCode(), delivery.country(), d.optionName(), d.townName());
        }
        order.startHold(QuoteDigest.of(priced.quote()), key);
        Order saved = repository.saveAndFlush(order);
        log.info("Recorded order id={} ({}) subtotal={} total={} {} items={} (PENDING: inventory not yet asked)", saved.id(), sale.kind(), saved.subtotal(), saved.total(),
                saved.currency(), saved.items().size());
        return new Begun(saved, true);
    }

    /** A repeat must be the same request: the key is not a way to change an order. */
    static void requireSameRequest(Order existing, String requestHash) {
        if (!requestHash.equals(existing.requestHash())) {
            throw new SalesProblem(HttpStatus.CONFLICT, "idempotency-key-reused",
                    "this Idempotency-Key was used for a different order: use a new key for a new order");
        }
    }
}

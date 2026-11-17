package com.oriah.order.service;

import com.oriah.observability.ResourceNotFoundException;
import com.oriah.order.exception.SalesProblem;
import com.oriah.order.service.Corrections.Correction;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * An exchange (Phase 12, plan 4.9; FINAL AM7): a return and the new order the customer took instead, LINKED. Nothing else happens: the refund for the
 * return is paid like any refund and the new order is paid like any sale, so no money is ever netted between them and no event is published. The link
 * is what a person (and later a tax invoice's credit note) reads to see that the two belong together.
 *
 * <p>One link per return and one per new order (the database holds both). There is no time rule between the two: an exchange agreed weeks after the
 * parcel came back is still an exchange.
 */
@Service
@Slf4j
public class Exchanges {

    /** {@code replay}: this pair was already linked. */
    @Schema(name = "Exchange")
    public record Exchange(UUID exchangeId, UUID returnId, UUID returnedOrderId, UUID newOrderId, boolean replay) {
    }

    private record Link(UUID id, UUID returnId, UUID newOrderId) {
    }

    private static final RowMapper<Link> LINK = (rs, n) -> new Link(rs.getObject("id", UUID.class), rs.getObject("return_id", UUID.class),
            rs.getObject("new_order_id", UUID.class));

    private final JdbcTemplate jdbc;
    private final Corrections corrections;
    private final OrderLocks locks;

    public Exchanges(JdbcTemplate jdbc, Corrections corrections, OrderLocks locks) {
        this.jdbc = jdbc;
        this.corrections = corrections;
        this.locks = locks;
    }

    /**
     * @param actor {@code user:<keycloak subject>} of the member of staff
     * @throws ResourceNotFoundException 404, no such return
     * @throws SalesProblem 404 {@code order-not-found}; 409 {@code return-not-received-yet}, {@code exchange-needs-another-order},
     *                      {@code not-an-exchange-order} (the new order was cancelled or lapsed), {@code exchange-already-recorded}
     */
    @Transactional
    public Exchange link(UUID returnId, UUID newOrderId, String actor) {
        Correction ret = corrections.find(returnId).filter(c -> Corrections.RETURN.equals(c.kind()))
                .orElseThrow(() -> new ResourceNotFoundException("Return", "id", returnId.toString()));
        // Both orders' locks, in id order (so two links can never wait on each other): two links for one return, a link racing the return's own
        // finish, and two returns reaching for the same new order all queue, and the second sees what the first wrote.
        for (UUID order : new java.util.TreeSet<>(List.of(ret.orderId(), newOrderId))) {
            jdbc.queryForList("SELECT id FROM orders WHERE id = ? FOR UPDATE", UUID.class, order);
        }
        Correction locked = corrections.lock(returnId);
        if (!Corrections.DONE.equals(locked.state())) {
            throw new SalesProblem(HttpStatus.CONFLICT, "return-not-received-yet", "this return is " + locked.state() + ": it is exchanged once the stock "
                    + "system has taken the pieces back").with("returnState", locked.state());
        }
        if (newOrderId.equals(ret.orderId())) {
            throw new SalesProblem(HttpStatus.CONFLICT, "exchange-needs-another-order", "an exchange links a return to ANOTHER order: the one the customer took instead");
        }
        String status = jdbc.queryForList("SELECT status FROM orders WHERE id = ?", String.class, newOrderId).stream().findFirst()
                .orElseThrow(() -> new SalesProblem(HttpStatus.NOT_FOUND, "order-not-found", "there is no order " + newOrderId));
        Optional<Link> ofReturn = jdbc.query("SELECT * FROM order_exchange WHERE return_id = ?", LINK, returnId).stream().findFirst();
        if (ofReturn.isPresent()) {
            if (ofReturn.get().newOrderId().equals(newOrderId)) {
                return new Exchange(ofReturn.get().id(), returnId, ret.orderId(), newOrderId, true);
            }
            throw alreadyRecorded("this return is already linked to order " + ofReturn.get().newOrderId());
        }
        List<Link> ofOrder = jdbc.query("SELECT * FROM order_exchange WHERE new_order_id = ?", LINK, newOrderId);
        if (!ofOrder.isEmpty()) {
            throw alreadyRecorded("that order is already the exchange for another return");
        }
        if ("CANCELLED".equals(status) || "EXPIRED".equals(status)) {
            throw new SalesProblem(HttpStatus.CONFLICT, "not-an-exchange-order", "that order is " + status + ": it is not what the customer took instead")
                    .with("status", status);
        }
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO order_exchange (id, return_id, new_order_id, linked_by, linked_at) VALUES (?, ?, ?, ?, ?)", id, returnId, newOrderId, actor,
                    Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MICROS)));
        } catch (org.springframework.dao.DuplicateKeyException raced) {
            // The locks above make this unreachable from here; the database's own rule is answered by name all the same.
            throw alreadyRecorded("this return or that order is already part of an exchange");
        }
        log.info("Return {} of order {} linked to new order {} as an exchange by {}", returnId, ret.orderId(), newOrderId, actor);
        return new Exchange(id, returnId, ret.orderId(), newOrderId, false);
    }

    private static SalesProblem alreadyRecorded(String what) {
        return new SalesProblem(HttpStatus.CONFLICT, "exchange-already-recorded", what + ": a return is exchanged once, for one order");
    }
}

package com.oriah.order.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.observability.ResourceNotFoundException;
import com.oriah.order.client.CatalogLookupClient;
import com.oriah.order.dto.RecordedOrderView;
import com.oriah.order.dto.RecordedViews;
import com.oriah.order.exception.SalesProblem;
import com.oriah.order.model.Channel;
import com.oriah.order.model.Order;
import com.oriah.order.model.OrderStatus;
import com.oriah.order.repository.OrderRepository;
import com.oriah.quote.DeliveryContact;

import lombok.extern.slf4j.Slf4j;

/**
 * What sales staff read of recorded orders (Phase 9): find one, read one, give the caller a receipt, and cancel one that is waiting for money. Only the
 * PHONE channel is reachable here: a web order, a missing one and one that is not a phone order all answer the same 404, so this door is no way to read a
 * shopper's order. Every answer is an allow-list; the person's contact details are written to no log (the read says only that it happened and which order).
 */
@Service
@Slf4j
public class RecordedReadService {

    static final int MAX_PAGE_SIZE = 50;
    private static final Pattern RECEIPT = Pattern.compile("^OR-\\d{6}$");
    private static final int NAME_PREFIX_MIN = 3;

    private final OrderRepository repository;
    private final JdbcTemplate jdbc;
    private final DispatchViews dispatchViews;
    private final OrderService orders;
    private final CatalogLookupClient catalogLookup;

    public RecordedReadService(OrderRepository repository, JdbcTemplate jdbc, DispatchViews dispatchViews, OrderService orders, CatalogLookupClient catalogLookup) {
        this.catalogLookup = catalogLookup;
        this.repository = repository;
        this.jdbc = jdbc;
        this.dispatchViews = dispatchViews;
        this.orders = orders;
    }

    /**
     * The boxes a salesperson can choose from, as catalog names them (read with the person's own token, outside any transaction).
     *
     * @throws com.oriah.order.exception.UpstreamUnavailableException catalog did not answer (503): never an empty list, which would read as "no boxes exist"
     */
    public List<CatalogLookupClient.Box> boxes() {
        try {
            List<CatalogLookupClient.Box> boxes = catalogLookup.packaging();
            return boxes == null ? List.of() : boxes;
        } catch (feign.FeignException e) {
            log.warn("The list of boxes was not answered: catalog status={}", e.status());
            throw new com.oriah.order.exception.UpstreamUnavailableException("catalog");
        }
    }

    private Order recorded(UUID id) {
        return repository.findById(id).filter(o -> o.channel() == Channel.PHONE).orElseThrow(() -> new ResourceNotFoundException("Order", "id", id.toString()));
    }

    @Transactional(readOnly = true)
    public RecordedOrderView detail(UUID id, String reader) {
        Order order = recorded(id);
        log.info("Recorded order {} read by {}", id, reader);
        return RecordedOrderView.read(order, dispatchViews.stepsOf(List.of(id)).get(id));
    }

    /**
     * @throws SalesProblem 409 {@code receipt-not-ready} until the order is paid (it has no receipt number before)
     */
    @Transactional(readOnly = true)
    public RecordedViews.Receipt receipt(UUID id) {
        Order order = recorded(id);
        if (order.receiptNumber() == null) {
            throw new SalesProblem(HttpStatus.CONFLICT, "receipt-not-ready", "this order has no receipt yet: it is " + order.status() + " and a receipt is given once it is paid");
        }
        return RecordedViews.Receipt.of(order);
    }

    /**
     * Cancels a recorded order that is waiting for money (CREATED): its hold goes, its recipient and address are forgotten (its caller is kept: money may still
     * arrive and need returning). A paid, lapsed or settling order is refused by the same rules as any order's cancellation (409).
     */
    public RecordedOrderView cancel(UUID id, String actor) {
        Order found = repository.findById(id).filter(o -> o.channel() == Channel.PHONE).orElseThrow(() -> new ResourceNotFoundException("Order", "id", id.toString()));
        return orders.transition(found.id(), OrderStatus.CANCELLED, actor).map(o -> RecordedOrderView.read(o, null))
                .orElseThrow(() -> new ResourceNotFoundException("Order", "id", id.toString()));
    }

    /**
     * Recorded orders, newest first. {@code q} finds by the caller's phone in any spelling (exact), a receipt number, a slip number (exact) or a name (a prefix of
     * three letters or more); {@code status} narrows by status. A page is at most {@value #MAX_PAGE_SIZE}.
     */
    @Transactional(readOnly = true)
    public RecordedViews.Page list(String q, String status, int page, int size) {
        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int pageNumber = Math.max(page, 0);
        StringBuilder where = new StringBuilder(" WHERE channel = 'PHONE'");
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            try {
                where.append(" AND status = ?");
                args.add(OrderStatus.valueOf(status.strip().toUpperCase(java.util.Locale.ROOT)).name());
            } catch (IllegalArgumentException unknown) {
                throw new SalesProblem(HttpStatus.BAD_REQUEST, "invalid-status", "status is one of " + java.util.Arrays.toString(OrderStatus.values()));
            }
        }
        String text = q == null ? "" : q.strip();
        if (!text.isEmpty()) {
            String phone = asPhone(text);
            if (phone != null) {
                where.append(" AND caller_phone = ?");
                args.add(phone);
            } else if (RECEIPT.matcher(text.toUpperCase(java.util.Locale.ROOT)).matches()) {
                where.append(" AND receipt_number = ?");
                args.add(text.toUpperCase(java.util.Locale.ROOT));
            } else {
                // A slip number exactly, or a name by its first letters: a short fragment of a name matches nothing (it would match half the book).
                where.append(" AND (slip_reference = ?");
                args.add(text);
                if (text.length() >= NAME_PREFIX_MIN) {
                    where.append(" OR lower(caller_name) LIKE ? ESCAPE '\\'");
                    args.add(escapeLike(text.toLowerCase(java.util.Locale.ROOT)) + "%");
                }
                where.append(")");
            }
        }
        Long total = jdbc.queryForObject("SELECT count(*) FROM orders" + where, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pageSize);
        pageArgs.add((long) pageNumber * pageSize);
        List<RecordedViews.Row> rows = jdbc.query("SELECT id, status, sale_kind, occurred_at, placed_at, caller_name, caller_phone, total, currency, receipt_number FROM orders"
                + where + " ORDER BY placed_at DESC, id LIMIT ? OFFSET ?", (rs, n) -> new RecordedViews.Row(rs.getObject("id", UUID.class), rs.getString("status"),
                rs.getString("sale_kind"), rs.getTimestamp("occurred_at").toInstant(), rs.getTimestamp("placed_at").toInstant(), rs.getString("caller_name"),
                RecordedOrderView.mask(rs.getString("caller_phone")), rs.getBigDecimal("total"), rs.getString("currency"), rs.getString("receipt_number")), pageArgs.toArray());
        return new RecordedViews.Page(rows, pageNumber, pageSize, total == null ? 0 : total);
    }

    /** The phone as it is kept ({@code +260...}) when the text reads as one, else null (a name, a receipt number or a slip is not a phone). */
    private static String asPhone(String text) {
        if (!text.matches("^[+0-9][0-9 ()+-]{6,}$")) {
            return null;
        }
        try {
            return DeliveryContact.phone(text);
        } catch (IllegalArgumentException unreadable) {
            return null;
        }
    }

    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}

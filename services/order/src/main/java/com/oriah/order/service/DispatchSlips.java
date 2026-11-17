package com.oriah.order.service;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.observability.ResourceNotFoundException;
import com.oriah.order.exception.SalesProblem;
import com.oriah.order.model.Order;
import com.oriah.order.model.OrderItem;
import com.oriah.order.model.OrderStatus;
import com.oriah.order.repository.OrderRepository;
import com.oriah.order.service.DispatchReadService.Address;
import com.oriah.order.service.DispatchReadService.Box;
import com.oriah.order.service.DispatchReadService.Slip;
import com.oriah.order.service.DispatchReadService.SlipDelivery;
import com.oriah.order.service.DispatchReadService.SlipLine;
import com.oriah.order.service.DispatchReadService.Viewer;
import com.oriah.order.service.DispatchService.Recorded;
import com.oriah.order.service.DispatchService.Step;

import lombok.extern.slf4j.Slf4j;

/**
 * The database half of a packing slip: one short read-only transaction, so {@link DispatchReadService} can ask catalog for the box names AFTER it has closed
 * (a bean of its own, because a method that calls another method on the same bean skips the transaction).
 */
@Service
@Slf4j
public class DispatchSlips {

    private final OrderRepository orders;
    private final DispatchService dispatch;
    private final BoxSwaps swaps;

    public DispatchSlips(OrderRepository orders, DispatchService dispatch, BoxSwaps swaps) {
        this.orders = orders;
        this.dispatch = dispatch;
        this.swaps = swaps;
    }

    /** The slip with the boxes' ids and no names yet. */
    @Transactional(readOnly = true)
    public Slip read(UUID orderId, Viewer viewer) {
        Order order = orders.findById(orderId).orElseThrow(() -> new ResourceNotFoundException("Order", "id", orderId.toString()));
        if (order.status() == OrderStatus.CANCELLED && order.paidAt() != null) {
            // Phase 12: a paid order that was cancelled. Said plainly, before anything personal is read (it has been forgotten anyway).
            throw new SalesProblem(HttpStatus.CONFLICT, "order-cancelled", "this order was paid and then cancelled: nothing more is packed, sent or handed over; any piece already packed goes back on the shelf");
        }
        if (order.paidAt() == null || List.of(OrderStatus.CREATED, OrderStatus.PAYMENT_SUBMITTED, OrderStatus.EXPIRED, OrderStatus.CANCELLED).contains(order.status())) {
            throw new SalesProblem(HttpStatus.CONFLICT, "order-not-paid", "this order is " + order.status() + ", not paid: there is nothing to pack or send yet");
        }
        log.info("Packing slip of order {} read by {}", orderId, viewer.actor());
        Map<Step, Recorded> steps = dispatch.stepsOf(orderId);
        Recorded sent = steps.get(Step.DISPATCHED);
        SlipDelivery delivery = null;
        if (!order.isSelfCollect() && order.hasDeliveryDetails() && (order.status() != OrderStatus.DELIVERED || viewer.mayViewDeliveredPerson())) {
            delivery = new SlipDelivery(order.deliveryOption(), order.deliveryOptionName(), order.deliveryPayer(), order.deliveryTownName(), order.recipientName(),
                    order.recipientPhone(), new Address(order.addressLine1(), order.addressLine2(), order.addressCity(), order.addressState(),
                            order.addressPostalCode(), order.addressCountry()));
        }
        // A pickup has no recipient to hand the parcel to, so the packer is told the caller's NAME (never the phone) for a phone sale, until it is handed over.
        String collector = order.isSelfCollect() && order.channel() == com.oriah.order.model.Channel.PHONE
                && (order.status() != OrderStatus.DELIVERED || viewer.mayViewDeliveredPerson()) ? order.callerName() : null;
        List<SlipLine> lines = order.items().stream()
                .sorted(Comparator.comparing(OrderItem::title).thenComparing(OrderItem::id))
                .map(i -> new SlipLine(i.id(), i.title(), i.quantity(), i.boxVariantId() == null ? null : new Box(i.boxVariantId(), null)))
                .toList();
        return new Slip(order.id(), order.receiptNumber(), order.status(), DispatchService.stage(order.status()), order.paidAt(), order.isSelfCollect(),
                FareRule.due(order.deliveryOption(), order.deliveryPayer()), swaps.isInFlight(orderId), delivery, lines,
                at(steps.get(Step.PACKED)), at(sent), sent == null ? null : sent.reference(), viewer.mayViewCost() && sent != null ? sent.farePaid() : null,
                at(steps.get(Step.DELIVERED)), true, null, collector);
    }

    private static Instant at(Recorded step) {
        return step == null ? null : step.occurredAt();
    }
}

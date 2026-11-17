package com.oriah.order.events;

import java.util.UUID;
import java.util.function.Consumer;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;
import org.springframework.transaction.PlatformTransactionManager;

import com.oriah.events.Inbox;
import com.oriah.order.events.InboundEvents.PaymentConfirmed;
import com.oriah.order.events.InboundEvents.PaymentRejected;
import com.oriah.order.events.InboundEvents.PaymentVoided;
import com.oriah.order.events.InboundEvents.ReservationExpired;
import com.oriah.order.service.OrderEnds;
import com.oriah.order.service.SettlementDriver;
import com.oriah.order.service.SettlementService;

import lombok.extern.slf4j.Slf4j;

/**
 * The two listeners of the sale (Phase 7). {@code paymentEvents} reads {@code oriah.payment}; {@code inventoryEvents} reads {@code oriah.inventory}.
 * Both go through {@link OrderInbox}: an event is applied once, in a transaction of its own, and a confirmation of money is then driven (inventory
 * asked to sell the pieces) outside it. A failure while driving never fails the event: the money is recorded, and the retry job tries again.
 *
 * <ul>
 *   <li>{@code payment.confirmed}: the money pays the sale, or is held for the customer ({@link SettlementService#receive});</li>
 *   <li>{@code payment.voided}: staff cancelled money that had no outcome yet ({@link SettlementService#voided});</li>
 *   <li>{@code payment.rejected}: staff could not find the money a claim named ({@link OrderEnds#rejected});</li>
 *   <li>{@code reservation.expired}: a hold lapsed ({@link OrderEnds#lapsed}).</li>
 * </ul>
 */
@Configuration
@Slf4j
public class SaleEventConsumers {

    @Bean
    public Consumer<Message<byte[]>> paymentEvents(Inbox inbox, PlatformTransactionManager transactions, SettlementService settlements, OrderEnds ends,
                                                   SettlementDriver driver, com.oriah.order.service.RefundFacts refunds) {
        return OrderInbox.handler(inbox, transactions, (type, payload) -> switch (type) {
            case OrderInbox.CONFIRMED -> settlements.receive(InboundEvents.read(payload, PaymentConfirmed.class));
            case OrderInbox.REJECTED -> {
                ends.rejected(InboundEvents.read(payload, PaymentRejected.class));
                yield null;
            }
            case OrderInbox.VOIDED -> {
                settlements.voided(InboundEvents.read(payload, PaymentVoided.class));
                yield null;
            }
            // Phase 12: what payment did about a refund order announced. Only remembered; a payout of HELD money is not a refund of a correction.
            case OrderInbox.PAID_OUT -> {
                InboundEvents.PaidOut paid = InboundEvents.read(payload, InboundEvents.PaidOut.class);
                if (paid.isRefund()) {
                    refunds.paidOut(paid.debtId(), paid.payoutId(), paid.paidAt());
                }
                yield null;
            }
            case OrderInbox.PAYOUT_VOIDED -> {
                InboundEvents.PayoutVoided voided = InboundEvents.read(payload, InboundEvents.PayoutVoided.class);
                if (voided.isRefund()) {
                    refunds.payoutVoided(voided.debtId(), voided.payoutId(), voided.voidedAt());
                }
                yield null;
            }
            case OrderInbox.REFUND_CLOSED -> {
                InboundEvents.RefundClosed closed = InboundEvents.read(payload, InboundEvents.RefundClosed.class);
                refunds.closed(closed.dueId(), closed.closedAt());
                yield null;
            }
            default -> throw new IllegalStateException("declared as handled but not applied: " + type);
        }, drivenBy(driver));
    }

    @Bean
    public Consumer<Message<byte[]>> inventoryEvents(Inbox inbox, PlatformTransactionManager transactions, OrderEnds ends, SettlementDriver driver) {
        return OrderInbox.handler(inbox, transactions, (type, payload) -> {
            if (!OrderInbox.EXPIRED.equals(type)) {
                throw new IllegalStateException("declared as handled but not applied: " + type);
            }
            ends.lapsed(InboundEvents.read(payload, ReservationExpired.class));
            return null;
        }, drivenBy(driver));
    }

    private static Consumer<UUID> drivenBy(SettlementDriver driver) {
        return confirmation -> {
            try {
                driver.drive(confirmation);
            } catch (RuntimeException e) {
                // The money is recorded. Whatever went wrong here, the retry job asks again; the event itself must not fail and be parked.
                log.warn("Confirmation {} could not be driven just now: {}", confirmation, e.getMessage());
            }
        };
    }
}

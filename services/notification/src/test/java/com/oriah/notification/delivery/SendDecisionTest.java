package com.oriah.notification.delivery;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.oriah.notification.config.EmailSetup;
import com.oriah.notification.config.EmailSetup.Mode;
import com.oriah.notification.contact.ContactBook.Contact;
import com.oriah.notification.delivery.SendDecision.NotSent;
import com.oriah.notification.delivery.SendDecision.Send;
import com.oriah.notification.delivery.SendDecision.ShopperFacts;
import com.oriah.notification.delivery.SendDecision.Wait;
import com.oriah.notification.model.StatusReason;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What becomes of one waiting message to a customer, one rule a test.
 */
class SendDecisionTest {

    private static final Instant NOW = Instant.parse("2026-11-09T07:31:00Z");
    private static final Duration RECHECK = Duration.ofMinutes(1);
    private static final Optional<Contact> CONFIRMED = Optional.of(new Contact("alice@oriah.local", true));
    private static final Optional<Contact> UNCONFIRMED = Optional.of(new Contact("alice@oriah.local", false));

    private static EmailSetup setup(Mode mode, boolean paused) {
        return new EmailSetup(mode, null, List.of(), List.of(), null, paused);
    }

    private static SendDecision.Decision decide(String type, Optional<Contact> contact, EmailSetup setup) {
        return SendDecision.forShopper(new ShopperFacts(type, contact, false, false, false), setup, NOW, RECHECK);
    }

    @Test
    @DisplayName("a confirmed address gets the full words")
    void aVerifiedShopperGetsTheFullWords() {
        assertThat(decide("order.paid", CONFIRMED, setup(Mode.CATCHER, false))).isEqualTo(new Send("alice@oriah.local", false));
        assertThat(decide("order.expired", CONFIRMED, setup(Mode.CATCHER, false))).isEqualTo(new Send("alice@oriah.local", false));
    }

    @Test
    @DisplayName("with no mail server, or with all email paused, nothing is sent and the reason says so")
    void sendingOff() {
        assertThat(decide("order.paid", CONFIRMED, setup(Mode.OFF, false))).isEqualTo(new NotSent(StatusReason.SENDING_OFF));
        assertThat(decide("order.paid", CONFIRMED, setup(Mode.CATCHER, true))).isEqualTo(new NotSent(StatusReason.SENDING_OFF));
        assertThat(decide("order.paid", Optional.empty(), setup(Mode.LIVE, true))).isEqualTo(new NotSent(StatusReason.SENDING_OFF));
    }

    @Test
    @DisplayName("a customer whose address is not known yet is looked at again in a minute; no try is spent")
    void noAddressYet() {
        assertThat(decide("order.paid", Optional.empty(), setup(Mode.CATCHER, false))).isEqualTo(new Wait(NOW.plus(RECHECK)));
    }

    @Test
    @DisplayName("an address nobody confirmed gets only the short note, and only for a message that confirmed money stands behind")
    void anUnverifiedAddress() {
        for (String type : List.of("order.paid", "order.fulfilled", "order.shipped", "order.delivered")) {
            assertThat(decide(type, UNCONFIRMED, setup(Mode.CATCHER, false))).as(type).isEqualTo(new Send("alice@oriah.local", true));
        }
        for (String type : List.of("order.expired", "order.created", "order.cancelled")) {
            assertThat(decide(type, UNCONFIRMED, setup(Mode.CATCHER, false))).as(type).isEqualTo(new NotSent(StatusReason.UNVERIFIED_ADDRESS));
        }
    }

    @Test
    @DisplayName("'ready to collect' is not sent once the order was handed over, or once a later 'on its way' was emailed")
    void readyToCollectIsSuperseded() {
        EmailSetup catcher = setup(Mode.CATCHER, false);

        assertThat(SendDecision.forShopper(new ShopperFacts("order.fulfilled", CONFIRMED, true, false, false), catcher, NOW, RECHECK))
                .isEqualTo(new NotSent(StatusReason.SUPERSEDED));
        assertThat(SendDecision.forShopper(new ShopperFacts("order.fulfilled", CONFIRMED, false, true, false), catcher, NOW, RECHECK))
                .isEqualTo(new NotSent(StatusReason.SUPERSEDED));
    }

    @Test
    @DisplayName("'on its way' is not sent once the order was delivered; a later 'on its way' sent does not hold itself back")
    void onItsWayIsSuperseded() {
        EmailSetup catcher = setup(Mode.CATCHER, false);

        assertThat(SendDecision.forShopper(new ShopperFacts("order.shipped", CONFIRMED, true, false, false), catcher, NOW, RECHECK))
                .isEqualTo(new NotSent(StatusReason.SUPERSEDED));
        assertThat(SendDecision.forShopper(new ShopperFacts("order.shipped", CONFIRMED, false, true, false), catcher, NOW, RECHECK))
                .isEqualTo(new Send("alice@oriah.local", false));
    }

    @Test
    @DisplayName("'we could not complete your order' is not sent once the order turned out to be paid")
    void theClosedOrderMessageIsSupersededByAPayment() {
        assertThat(SendDecision.forShopper(new ShopperFacts("order.expired", CONFIRMED, false, false, true), setup(Mode.CATCHER, false), NOW, RECHECK))
                .isEqualTo(new NotSent(StatusReason.SUPERSEDED));
    }

    @Test
    @DisplayName("'payment received' and 'delivered' are never held back by anything later")
    void paymentReceivedIsNeverSuperseded() {
        EmailSetup catcher = setup(Mode.CATCHER, false);

        assertThat(SendDecision.forShopper(new ShopperFacts("order.paid", CONFIRMED, true, true, true), catcher, NOW, RECHECK))
                .isEqualTo(new Send("alice@oriah.local", false));
        assertThat(SendDecision.forShopper(new ShopperFacts("order.delivered", CONFIRMED, true, true, true), catcher, NOW, RECHECK))
                .isEqualTo(new Send("alice@oriah.local", false));
    }

    @Test
    @DisplayName("on a stack that is not production, an address that could be a real mailbox is never handed over; on a live one it is")
    void theSandbox() {
        Optional<Contact> real = Optional.of(new Contact("nora@example.com", true));

        assertThat(decide("order.paid", real, setup(Mode.CATCHER, false))).isEqualTo(new NotSent(StatusReason.SANDBOX));
        assertThat(decide("order.paid", real, setup(Mode.LIVE, false))).isEqualTo(new Send("nora@example.com", false));
    }

    @Test
    @DisplayName("a decision never prints the address")
    void noAddressInPrint() {
        assertThat(decide("order.paid", CONFIRMED, setup(Mode.CATCHER, false)).toString()).doesNotContain("alice");
    }
}

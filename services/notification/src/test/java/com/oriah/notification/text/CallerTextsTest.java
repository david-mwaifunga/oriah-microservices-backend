package com.oriah.notification.text;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.oriah.notification.model.MessageKind;
import com.oriah.notification.text.MessageTexts.OrderWords;
import com.oriah.notification.text.MessageTexts.ShopLines;
import com.oriah.notification.text.MessageTexts.Text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The words the system writes for a person to send to a customer who ordered by phone. The subject is a heading on the sales screen; the body
 * is what goes into WhatsApp, so it opens with the shop's name (the customer sees a number, not a letterhead) and is short.
 */
class CallerTextsTest {

    private static final UUID ORDER = UUID.fromString("8a1f4b2e-3c5d-4e6f-9a0b-1c2d3e4f5a6b");
    private static final ShopLines NO_LINES = new ShopLines("", "", "");

    private static OrderWords order(String receipt, boolean recipientPays) {
        return new OrderWords(ORDER, receipt, new BigDecimal("5875.00"), "ZMW", "SELF_COLLECT", recipientPays);
    }

    @Test
    @DisplayName("C1, payment received: the amount and the receipt number")
    void paymentReceived() {
        Text text = MessageTexts.forCaller(MessageKind.PAID, order("OR-000123", false), NO_LINES);

        assertThat(text.subject()).isEqualTo("Payment received");
        assertThat(text.body()).isEqualTo("Oriah Jewelry: we have received your payment of K5,875.00. Your receipt number is OR-000123. Thank you.");
        assertThat(MessageTexts.forCaller(MessageKind.PAID, order(null, false), NO_LINES).body())
                .isEqualTo("Oriah Jewelry: we have received your payment of K5,875.00. Thank you.");
    }

    @Test
    @DisplayName("C2, ready to collect: the order by its receipt number, and where to collect once the owner has written it")
    void readyToCollect() {
        Text text = MessageTexts.forCaller(MessageKind.READY_TO_COLLECT, order("OR-000123", false), NO_LINES);

        assertThat(text.subject()).isEqualTo("Ready to collect");
        assertThat(text.body()).isEqualTo("Oriah Jewelry: your order OR-000123 is packed and ready to collect.");
        assertThat(MessageTexts.forCaller(MessageKind.READY_TO_COLLECT, order("OR-000123", false),
                new ShopLines("", "Collect at Shop 12, Cairo Road, Lusaka.", "")).body())
                .isEqualTo("Oriah Jewelry: your order OR-000123 is packed and ready to collect. Collect at Shop 12, Cairo Road, Lusaka.");
    }

    @Test
    @DisplayName("C3, on its way; and when the customer pays the courier, it says so")
    void onItsWay() {
        Text text = MessageTexts.forCaller(MessageKind.ON_ITS_WAY, order("OR-000123", false), NO_LINES);

        assertThat(text.subject()).isEqualTo("On its way");
        assertThat(text.body()).isEqualTo("Oriah Jewelry: your order OR-000123 is on its way to you.");
        assertThat(MessageTexts.forCaller(MessageKind.ON_ITS_WAY, order("OR-000123", true), NO_LINES).body())
                .isEqualTo("Oriah Jewelry: your order OR-000123 is on its way to you. You pay the courier directly when it arrives.");
    }

    @Test
    @DisplayName("there are words for those three and no others: a caller is on the phone with staff for everything else")
    void onlyThoseThree() {
        for (MessageKind kind : MessageKind.values()) {
            boolean written = kind == MessageKind.PAID || kind == MessageKind.READY_TO_COLLECT || kind == MessageKind.ON_ITS_WAY;
            assertThat(MessageTexts.writtenForACaller(kind)).as(kind.name()).isEqualTo(written);
            if (!written) {
                assertThatThrownBy(() -> MessageTexts.forCaller(kind, order(null, false), NO_LINES)).isInstanceOf(IllegalArgumentException.class);
            }
        }
    }

    @Test
    @DisplayName("plain letters only, and no name, phone number or order id")
    void plainAndImpersonal() {
        for (MessageKind kind : new MessageKind[] {MessageKind.PAID, MessageKind.READY_TO_COLLECT, MessageKind.ON_ITS_WAY}) {
            Text text = MessageTexts.forCaller(kind, order("OR-000123", true), NO_LINES);

            assertThat(text.subject() + text.body()).matches("\\p{ASCII}+").doesNotContain(ORDER.toString());
        }
    }
}

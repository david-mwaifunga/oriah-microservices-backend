package com.oriah.notification.text;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.oriah.notification.model.MessageKind;
import com.oriah.notification.text.MessageTexts.OrderWords;
import com.oriah.notification.text.MessageTexts.ShopLines;
import com.oriah.notification.text.MessageTexts.Text;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every message a customer can get, word for word. The words are the owner's to change, and a change is made here first.
 *
 * <p>Rules every text keeps: money is written K5,875.00; the order is named by its receipt number once paid and by an eight-character code before;
 * plain letters only; no name, phone number, address or payment reference; nothing that is another service's setting (how long a hold lasts, how
 * soon something happens); and a line only the owner can write (how to pay, where to collect, how to reach the shop) appears only once she has
 * written it.
 */
class ShopperTextsTest {

    private static final UUID ORDER = UUID.fromString("8a1f4b2e-3c5d-4e6f-9a0b-1c2d3e4f5a6b");
    private static final ShopLines NO_LINES = new ShopLines("", "", "");
    private static final ShopLines LINES = new ShopLines(
            "Pay to MTN 0960 000 000 (Oriah Jewelry).", "Collect at Shop 12, Cairo Road, Lusaka.", "Call the shop on 0211 000 000.");

    private static OrderWords unpaid() {
        return new OrderWords(ORDER, null, new BigDecimal("5875.00"), "ZMW", "DELIVERY", false);
    }

    private static OrderWords paid(String deliveryOption) {
        return new OrderWords(ORDER, "OR-000123", new BigDecimal("5875.00"), "ZMW", deliveryOption, false);
    }

    @Test
    @DisplayName("M1, order placed: the pieces are held and nothing is paid yet")
    void placed() {
        Text text = MessageTexts.forShopper(MessageKind.PLACED, unpaid(), NO_LINES);

        assertThat(text.subject()).isEqualTo("We are holding your order");
        assertThat(text.body()).isEqualTo("Thank you. We are holding the pieces in order 8A1F4B2E for you. The total is K5,875.00. "
                + "Nothing is paid yet. Please pay, then open your order on our website and enter your payment reference, "
                + "so that we keep the pieces for you while we check.");
    }

    @Test
    @DisplayName("M2, paid: the amount, the receipt number, what happens next, and that this is not a tax invoice")
    void paidDelivery() {
        Text text = MessageTexts.forShopper(MessageKind.PAID, paid("DELIVERY"), NO_LINES);

        assertThat(text.subject()).isEqualTo("Payment received - thank you");
        assertThat(text.body()).isEqualTo("We have received your payment of K5,875.00 for order 8A1F4B2E. Your receipt number is OR-000123. "
                + "We will tell you when your order is on its way. This message confirms your payment. It is not a tax invoice.");
    }

    @Test
    @DisplayName("M2 for an order the customer collects says so")
    void paidPickup() {
        assertThat(MessageTexts.forShopper(MessageKind.PAID, paid("SELF_COLLECT"), NO_LINES).body())
                .isEqualTo("We have received your payment of K5,875.00 for order 8A1F4B2E. Your receipt number is OR-000123. "
                        + "We will tell you when your order is ready to collect. This message confirms your payment. It is not a tax invoice.");
    }

    @Test
    @DisplayName("M2 for a payment event from before receipt numbers leaves that sentence out")
    void paidWithoutAReceiptNumberLeavesTheSentenceOut() {
        OrderWords old = new OrderWords(ORDER, null, new BigDecimal("200.00"), "ZMW", null, false);

        assertThat(MessageTexts.forShopper(MessageKind.PAID, old, NO_LINES).body())
                .isEqualTo("We have received your payment of K200.00 for order 8A1F4B2E. "
                        + "We will tell you when your order is on its way. This message confirms your payment. It is not a tax invoice.");
    }

    @Test
    @DisplayName("M3, ready to collect: names the order by its receipt number and asks for nothing to be brought")
    void readyToCollect() {
        Text text = MessageTexts.forShopper(MessageKind.READY_TO_COLLECT, paid("SELF_COLLECT"), NO_LINES);

        assertThat(text.subject()).isEqualTo("Your order is ready to collect");
        assertThat(text.body()).isEqualTo("Order OR-000123 is packed and ready for you to collect.");
        assertThat(text.body()).doesNotContainIgnoringCase("bring");
    }

    @Test
    @DisplayName("M4, packed for delivery")
    void packed() {
        Text text = MessageTexts.forShopper(MessageKind.PACKED, paid("DELIVERY"), NO_LINES);

        assertThat(text.subject()).isEqualTo("Your order is packed");
        assertThat(text.body()).isEqualTo("Order OR-000123 is packed. We will tell you when it is on its way.");
    }

    @Test
    @DisplayName("M5, on its way; and when the customer pays the courier, it says so")
    void aRecipientPaidCourierIsSaidOnTheWay() {
        Text text = MessageTexts.forShopper(MessageKind.ON_ITS_WAY, paid("DELIVERY"), NO_LINES);
        assertThat(text.subject()).isEqualTo("Your order is on its way");
        assertThat(text.body()).isEqualTo("Order OR-000123 has left our shop and is on its way to you.");

        OrderWords payOnArrival = new OrderWords(ORDER, "OR-000123", new BigDecimal("5875.00"), "ZMW", "DELIVERY", true);
        assertThat(MessageTexts.forShopper(MessageKind.ON_ITS_WAY, payOnArrival, NO_LINES).body())
                .isEqualTo("Order OR-000123 has left our shop and is on its way to you. You pay the courier directly when it arrives.");
    }

    @Test
    @DisplayName("M6 delivered and M7 collected")
    void deliveredAndCollected() {
        Text delivered = MessageTexts.forShopper(MessageKind.DELIVERED, paid("DELIVERY"), NO_LINES);
        assertThat(delivered.subject()).isEqualTo("Your order has been delivered");
        assertThat(delivered.body()).isEqualTo("Order OR-000123 has been delivered. We hope you love it.");

        Text collected = MessageTexts.forShopper(MessageKind.COLLECTED, paid("SELF_COLLECT"), NO_LINES);
        assertThat(collected.subject()).isEqualTo("Your order has been collected");
        assertThat(collected.body()).isEqualTo("Order OR-000123 has been collected. We hope you love it.");
    }

    @Test
    @DisplayName("M8, cancelled: promises no refund, because only an unpaid order can be cancelled")
    void aCancelledOrderNoLongerPromisesARefund() {
        Text text = MessageTexts.forShopper(MessageKind.CANCELLED, unpaid(), NO_LINES);

        assertThat(text.subject()).isEqualTo("Your order was cancelled");
        assertThat(text.body()).isEqualTo("Order 8A1F4B2E has been cancelled and the pieces are no longer held for you. "
                + "If you have already sent money for it, please contact us.");
        assertThat(text.body()).doesNotContainIgnoringCase("refund");
    }

    @Test
    @DisplayName("M9, expired with no payment claimed: names no figure")
    void anUnclaimedExpiryNamesNoFigure() {
        Text text = MessageTexts.forShopper(MessageKind.EXPIRED_UNPAID, unpaid(), NO_LINES);

        assertThat(text.subject()).isEqualTo("Your order has expired");
        assertThat(text.body()).isEqualTo("Order 8A1F4B2E was not paid in time, so the pieces we were holding for you are back on sale. "
                + "If you have already paid, please contact us with your payment reference.");
        assertThat(text.body()).doesNotContain("K5,875");
    }

    @Test
    @DisplayName("M10, an order that ends after the customer said they paid: true whether the claim was rejected, time ran out, or the money came too late")
    void theClosedOrderTextIsTrueWhenTheMoneyWasConfirmedToo() {
        Text text = MessageTexts.forShopper(MessageKind.PAYMENT_NOT_CONFIRMED, unpaid(), NO_LINES);

        assertThat(text.subject()).isEqualTo("We could not complete your order");
        assertThat(text.body()).isEqualTo("We could not complete order 8A1F4B2E with the payment you told us about, so the order has been closed "
                + "and the pieces are no longer held for you. If you did pay, your money is safe with us: please contact us with your payment "
                + "reference and we will put it right.");
        assertThat(text.body()).doesNotContain("could not confirm").doesNotContain("not paid");
    }

    @Test
    @DisplayName("S1, the short note for an address nobody has confirmed: no amount, no receipt number, no place, no order")
    void theShortFormNamesNoAmountReceiptPlaceOrOrder() {
        Text text = MessageTexts.shortForm();

        assertThat(text.subject()).isEqualTo("News about your Oriah order");
        assertThat(text.body()).isEqualTo("There is news about your order. Please sign in on our website to read it.");
    }

    @Test
    @DisplayName("a line only the owner can write appears once she has written it, and only on the messages it belongs to")
    void anOwnerLineAppearsOnlyWhenFilled() {
        assertThat(MessageTexts.forShopper(MessageKind.PLACED, unpaid(), LINES).body())
                .endsWith("while we check. Pay to MTN 0960 000 000 (Oriah Jewelry).");
        assertThat(MessageTexts.forShopper(MessageKind.READY_TO_COLLECT, paid("SELF_COLLECT"), LINES).body())
                .isEqualTo("Order OR-000123 is packed and ready for you to collect. Collect at Shop 12, Cairo Road, Lusaka.");
        for (MessageKind kind : new MessageKind[] {MessageKind.CANCELLED, MessageKind.EXPIRED_UNPAID, MessageKind.PAYMENT_NOT_CONFIRMED}) {
            assertThat(MessageTexts.forShopper(kind, unpaid(), LINES).body()).as(kind.name()).endsWith(" Call the shop on 0211 000 000.");
        }
        assertThat(MessageTexts.forShopper(MessageKind.PAID, paid("DELIVERY"), LINES).body())
                .doesNotContain("MTN").doesNotContain("Cairo Road").doesNotContain("0211");
        // A line of spaces is a line not written.
        assertThat(MessageTexts.forShopper(MessageKind.CANCELLED, unpaid(), new ShopLines(null, null, "  ")).body())
                .endsWith("please contact us.");
    }

    @Test
    @DisplayName("an amount in another currency keeps its code")
    void anotherCurrencyKeepsItsCode() {
        OrderWords dollars = new OrderWords(ORDER, null, new BigDecimal("200.00"), "USD", "DELIVERY", false);

        assertThat(MessageTexts.forShopper(MessageKind.PLACED, dollars, NO_LINES).body()).contains("The total is 200.00 USD.");
    }

    @Test
    @DisplayName("every kind of message has its words, in plain letters, and none states a setting of another service")
    void everyTextIsPlainAndStatesNoSetting() {
        for (MessageKind kind : MessageKind.values()) {
            for (OrderWords order : Arrays.asList(unpaid(), paid("DELIVERY"), paid("SELF_COLLECT"))) {
                Text text = MessageTexts.forShopper(kind, order, NO_LINES);
                String all = text.subject() + " " + text.body();

                assertThat(all).as(kind.name()).isNotBlank().matches("\\p{ASCII}+");
                assertThat(all).as(kind.name()).doesNotContainIgnoringCase("minute").doesNotContainIgnoringCase("hour")
                        .doesNotContain(ORDER.toString());
            }
        }
        assertThat(MessageTexts.shortForm().body()).matches("\\p{ASCII}+");
    }

    @Test
    @DisplayName("which message an event is: by its type, by how the order reaches the customer, and by whether a payment had been claimed")
    void theKindOfAMessage() {
        assertThat(MessageKind.of("order.created", "DELIVERY", null)).isEqualTo(MessageKind.PLACED);
        assertThat(MessageKind.of("order.paid", "DELIVERY", "PAYMENT_SUBMITTED")).isEqualTo(MessageKind.PAID);
        assertThat(MessageKind.of("order.fulfilled", "SELF_COLLECT", "PAID")).isEqualTo(MessageKind.READY_TO_COLLECT);
        assertThat(MessageKind.of("order.fulfilled", "DELIVERY", "PAID")).isEqualTo(MessageKind.PACKED);
        assertThat(MessageKind.of("order.shipped", "DELIVERY", "FULFILLED")).isEqualTo(MessageKind.ON_ITS_WAY);
        assertThat(MessageKind.of("order.delivered", "DELIVERY", "SHIPPED")).isEqualTo(MessageKind.DELIVERED);
        assertThat(MessageKind.of("order.delivered", "SELF_COLLECT", "FULFILLED")).isEqualTo(MessageKind.COLLECTED);
        assertThat(MessageKind.of("order.cancelled", "DELIVERY", "CREATED")).isEqualTo(MessageKind.CANCELLED);
        assertThat(MessageKind.of("order.expired", "DELIVERY", "CREATED")).isEqualTo(MessageKind.EXPIRED_UNPAID);
        assertThat(MessageKind.of("order.expired", "DELIVERY", "PAYMENT_SUBMITTED")).isEqualTo(MessageKind.PAYMENT_NOT_CONFIRMED);
    }

    @Test
    @DisplayName("an order with no delivery option recorded gets the delivery wording, never a guess that it was collected")
    void anUnknownDeliveryOptionGetsTheDeliveryWording() {
        assertThat(MessageKind.of("order.fulfilled", null, "PAID")).isEqualTo(MessageKind.PACKED);
        assertThat(MessageKind.of("order.delivered", null, "SHIPPED")).isEqualTo(MessageKind.DELIVERED);
    }

    @Test
    @DisplayName("an event type with no words is refused at once, by name")
    void aTypeWithNoWordsIsRefused() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> MessageKind.of("order.refunded", "DELIVERY", "PAID"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("order.refunded");
    }
}

package com.oriah.notification.text;

import java.math.BigDecimal;
import java.util.UUID;

import com.oriah.notification.model.MessageKind;

/**
 * Every word notification writes to a person, in one place, so the owner's wording is changed here and nowhere else.
 *
 * <p>Rules every text keeps (each has a test):
 * <ul>
 *   <li>money is written {@code K5,875.00} ({@link Kwacha}); an order is named by its receipt number once paid, by an eight-character code before
 *       ({@link OrderName});</li>
 *   <li>plain letters only (no dash a phone might show as a box);</li>
 *   <li>no name, phone number, address or payment reference;</li>
 *   <li>no figure that is another service's setting (how long a hold lasts, how soon something happens);</li>
 *   <li>an amount is printed only from the event that caused the message, never one remembered from earlier: a total can change without an event;</li>
 *   <li>a line only the owner can write (how to pay, where to collect, how to reach the shop) appears only once she has written it, and the text
 *       reads correctly without it.</li>
 * </ul>
 */
public final class MessageTexts {

    private static final String COLLECTED_BY_THE_CUSTOMER = "SELF_COLLECT";

    private MessageTexts() {
    }

    /** A message's two parts: an email's subject (a heading in the website's bell) and its words. */
    public record Text(String subject, String body) {
    }

    /**
     * The shop's own public details, written by the owner ({@code oriah.notification.shop.*}); each is left out of a message while it is empty.
     *
     * @param payLine     how to pay (the shop's mobile-money numbers and bank account)
     * @param collectLine where and when to collect
     * @param contactLine how a customer reaches the shop
     */
    public record ShopLines(String payLine, String collectLine, String contactLine) {
    }

    /**
     * What a message says about its order.
     *
     * @param receiptNumber        the receipt number once notification has learnt it (the order was paid); null before
     * @param total                the total in the event that caused this message; printed only by "placed" and "paid"
     * @param deliveryOption       how the order reaches the customer; null on an old order
     * @param recipientPaysCourier true when the customer pays the courier on arrival (the dispatch step said so)
     */
    public record OrderWords(UUID orderId, String receiptNumber, BigDecimal total, String currency, String deliveryOption,
                             boolean recipientPaysCourier) {

        String code() {
            return OrderName.code(orderId);
        }

        String name() {
            return OrderName.of(orderId, receiptNumber);
        }

        String money() {
            return Kwacha.write(total, currency);
        }

        boolean collects() {
            return COLLECTED_BY_THE_CUSTOMER.equals(deliveryOption);
        }
    }

    /** The words for a customer with an account: shown in the website's bell, and for some kinds also emailed. */
    public static Text forShopper(MessageKind kind, OrderWords order, ShopLines shop) {
        return switch (kind) {
            case PLACED -> new Text("We are holding your order",
                    "Thank you. We are holding the pieces in order " + order.code() + " for you. The total is " + order.money() + ". "
                            + "Nothing is paid yet. Please pay, then open your order on our website and enter your payment reference, "
                            + "so that we keep the pieces for you while we check." + line(shop.payLine()));
            case PAID -> new Text("Payment received - thank you",
                    "We have received your payment of " + order.money() + " for order " + order.code() + "."
                            + (known(order.receiptNumber()) ? " Your receipt number is " + order.receiptNumber() + "." : "")
                            + (order.collects() ? " We will tell you when your order is ready to collect."
                                    : " We will tell you when your order is on its way.")
                            + " This message confirms your payment. It is not a tax invoice.");
            case READY_TO_COLLECT -> new Text("Your order is ready to collect",
                    "Order " + order.name() + " is packed and ready for you to collect." + line(shop.collectLine()));
            case PACKED -> new Text("Your order is packed",
                    "Order " + order.name() + " is packed. We will tell you when it is on its way.");
            case ON_ITS_WAY -> new Text("Your order is on its way",
                    "Order " + order.name() + " has left our shop and is on its way to you."
                            + (order.recipientPaysCourier() ? " You pay the courier directly when it arrives." : ""));
            case DELIVERED -> new Text("Your order has been delivered",
                    "Order " + order.name() + " has been delivered. We hope you love it.");
            case COLLECTED -> new Text("Your order has been collected",
                    "Order " + order.name() + " has been collected. We hope you love it.");
            case CANCELLED -> new Text("Your order was cancelled",
                    "Order " + order.name() + " has been cancelled and the pieces are no longer held for you. "
                            + "If you have already sent money for it, please contact us." + line(shop.contactLine()));
            case EXPIRED_UNPAID -> new Text("Your order has expired",
                    "Order " + order.name() + " was not paid in time, so the pieces we were holding for you are back on sale. "
                            + "If you have already paid, please contact us with your payment reference." + line(shop.contactLine()));
            // The same event ends an order whose claim staff rejected, whose time ran out, and whose money DID arrive but could no longer pay it
            // (the piece was sold). So the words never say the payment was not confirmed, and they say the money is safe.
            case PAYMENT_NOT_CONFIRMED -> new Text("We could not complete your order",
                    "We could not complete order " + order.name() + " with the payment you told us about, so the order has been closed "
                            + "and the pieces are no longer held for you. If you did pay, your money is safe with us: please contact us with "
                            + "your payment reference and we will put it right." + line(shop.contactLine()));
        };
    }

    /**
     * What is emailed, in place of the stored words, to an address nobody has confirmed: it may be a stranger's (a mistyped address), so it learns
     * no amount, no receipt number, no place and no order. The full words are on the website.
     */
    public static Text shortForm() {
        return new Text("News about your Oriah order", "There is news about your order. Please sign in on our website to read it.");
    }

    private static boolean known(String text) {
        return text != null && !text.isBlank();
    }

    /** An owner's line as one more sentence, or nothing while she has not written it. */
    private static String line(String ownerLine) {
        return known(ownerLine) ? " " + ownerLine.trim() : "";
    }
}

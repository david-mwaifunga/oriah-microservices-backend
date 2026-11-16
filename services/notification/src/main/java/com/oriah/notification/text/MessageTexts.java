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

    /**
     * Whether words are written for a customer who ordered by phone: payment received, ready to collect, on its way. For everything else
     * (placed, cancelled, expired, delivered) the caller is on the phone with staff, or already has the parcel.
     */
    public static boolean writtenForACaller(MessageKind kind) {
        return kind == MessageKind.PAID || kind == MessageKind.READY_TO_COLLECT || kind == MessageKind.ON_ITS_WAY;
    }

    /**
     * The words for a person to send to a customer who ordered by phone. The system sends nothing: the subject is a heading on the sales
     * screen, and the body is what staff paste into WhatsApp, so it opens with the shop's name and is short.
     *
     * @throws IllegalArgumentException for a kind no words are written for ({@link #writtenForACaller})
     */
    public static Text forCaller(MessageKind kind, OrderWords order, ShopLines shop) {
        return switch (kind) {
            case PAID -> new Text("Payment received",
                    "Oriah Jewelry: we have received your payment of " + order.money() + "."
                            + (known(order.receiptNumber()) ? " Your receipt number is " + order.receiptNumber() + "." : "") + " Thank you.");
            case READY_TO_COLLECT -> new Text("Ready to collect",
                    "Oriah Jewelry: your order " + order.name() + " is packed and ready to collect." + line(shop.collectLine()));
            case ON_ITS_WAY -> new Text("On its way",
                    "Oriah Jewelry: your order " + order.name() + " is on its way to you."
                            + (order.recipientPaysCourier() ? " You pay the courier directly when it arrives." : ""));
            default -> throw new IllegalArgumentException("no words are written for a caller about " + kind);
        };
    }

    // ---- alerts to staff -------------------------------------------------------------------------------------------------------------------
    //
    // An alert's subject is its one line in a list; its body is the whole email. No amount is printed unless the fact that raised the alert
    // carried it. No text tells staff to reject a payment because the money cannot be found: the client's rule is that a claim is rejected only
    // on proof that it is false, "never because the money has not shown yet, which is what the 24 hours are for".

    private static final String WHEN_A_CLAIM_MAY_BE_REJECTED = "Reject the payment only if the provider tells you there is no such payment, "
            + "or the reference has already paid another order.";

    /**
     * A1: a customer says they have paid. It names NO amount: the claim carries none, and an order's total can change without an event (a
     * delivery waiver). Staff read the amount on the Awaiting payment list.
     *
     * @param holdEnds when the pieces stop being held for this customer
     */
    public static Text paymentToConfirm(UUID orderId, String method, java.time.Instant holdEnds, java.time.ZoneId zone) {
        String code = OrderName.code(orderId);
        return new Text("A customer says they have paid - order " + code,
                "A customer says they have paid for order " + code + " by " + methodWords(method) + ". Please check that the money has arrived: "
                        + Screens.AWAITING_PAYMENT + " shows the amount to look for and the reference the customer gave. When you find it, "
                        + "confirm it there. If the money has not shown yet, do nothing: the pieces stay held for this customer until "
                        + LusakaTime.write(holdEnds, zone) + ". " + WHEN_A_CLAIM_MAY_BE_REJECTED);
    }

    /** A2: the one reminder before the pieces are released. */
    public static Text paymentToConfirmReminder(UUID orderId, String method, java.time.Instant holdEnds, java.time.ZoneId zone) {
        String code = OrderName.code(orderId);
        String when = LusakaTime.write(holdEnds, zone);
        return new Text("Still waiting - order " + code + " will be released at " + when,
                "Order " + code + ": the customer told us they paid by " + methodWords(method) + ", and the order is still not marked as paid. "
                        + "The pieces will be released at " + when + ". If the money has arrived, please confirm it now on "
                        + Screens.AWAITING_PAYMENT + ". If it has not shown, there is nothing to do: the order will close by itself and the "
                        + "customer will see that on the website. " + WHEN_A_CLAIM_MAY_BE_REJECTED);
    }

    /** A10: more payment claims in a day than are emailed one by one. */
    public static Text claimEmailLimit(int limit) {
        return new Text("Many customers say they have paid - we have stopped emailing each one",
                "More than " + limit + " customers said in the last day that they have paid. We have stopped emailing you about each new one "
                        + "for now. Please open " + Screens.AWAITING_PAYMENT + ": every one is there. Alerts about other things are still "
                        + "emailed.");
    }

    /** A9: the mail server has been failing for a while. Listed and logged, never emailed (it could not be). */
    public static Text emailDown(java.time.Instant since, java.time.ZoneId zone) {
        return new Text("Emails are not going out",
                "Emails to customers and to you have not been going out since " + LusakaTime.write(since, zone) + ". Messages are kept and "
                        + "will be sent when email works again, unless they become too old. Alerts that could not be emailed will be emailed "
                        + "then.");
    }

    /** A9b: the mail server refused one of the alert mailboxes. Listed and logged, never emailed. */
    public static Text alertMailboxRefused() {
        return new Text("An alert email address is being refused",
                "The mail server refused an alert email to one of the alert addresses you gave us. Please check those addresses. Until this "
                        + "is fixed, alerts may not reach that address by email; they are still on this list.");
    }

    /** A payment method as a person says it. A code nobody taught this service is printed as it is, never guessed at. */
    public static String methodWords(String method) {
        if (method == null || method.isBlank()) {
            return "a method we do not know";
        }
        return switch (method) {
            case "MTN" -> "MTN mobile money";
            case "AIRTEL" -> "Airtel Money";
            case "ZAMTEL" -> "Zamtel mobile money";
            case "BANK_TRANSFER" -> "bank transfer";
            case "CASH" -> "cash";
            default -> method;
        };
    }

    private static boolean known(String text) {
        return text != null && !text.isBlank();
    }

    /** An owner's line as one more sentence, or nothing while she has not written it. */
    private static String line(String ownerLine) {
        return known(ownerLine) ? " " + ownerLine.trim() : "";
    }
}

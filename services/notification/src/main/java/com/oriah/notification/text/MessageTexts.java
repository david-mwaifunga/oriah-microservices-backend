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

    /**
     * What a message about a cancelled paid order or a return says. Every figure is from the event that caused the message.
     *
     * @param pieces              how many pieces came back (a return) or were on the order (a cancellation)
     * @param forPieces           what is owed for the pieces: their list price less their discount
     * @param forDelivery         the delivery charge that is paid back (a cancellation before dispatch only); zero for a return
     * @param owed                both together
     * @param customerBearsCharge true when the charge for sending the money back comes off what the customer receives (the event says who)
     * @param pieceUnavailable    true when the shop cancelled because a piece was no longer there: the words say sorry
     * @param moneyRecordCancelled true when the money record that paid the sale was itself cancelled since: nobody knows yet whether the money
     *                            arrived, so no amount is promised
     */
    public record RefundWords(UUID orderId, String receiptNumber, int pieces, BigDecimal forPieces, BigDecimal forDelivery, BigDecimal owed, String currency,
                              boolean customerBearsCharge, boolean pieceUnavailable, boolean moneyRecordCancelled) {

        String name() {
            return OrderName.of(orderId, receiptNumber);
        }

        String pieceCount() {
            return pieces == 1 ? "1 piece" : pieces + " pieces";
        }

        /** "K225.00: K180.00 for your pieces and K45.00 for delivery", or "K180.00 for your pieces" when no delivery is paid back. */
        String whatIsPaidBack() {
            return forDelivery.signum() > 0
                    ? Kwacha.write(owed, currency) + ": " + Kwacha.write(forPieces, currency) + " for your pieces and " + Kwacha.write(forDelivery, currency) + " for delivery"
                    : Kwacha.write(owed, currency) + " for your pieces";
        }

        String whoBearsTheCharge() {
            return customerBearsCharge ? " The charge for sending the money is taken from this amount." : " We carry the charge for sending the money.";
        }
    }

    private static final String WE_WILL_BE_IN_TOUCH = " We will be in touch about how you would like to be paid.";
    private static final String ABOUT_ANY_MONEY = " We will contact you about any money you sent us.";

    /** A paid order was cancelled before it left the shop (Phase 12): what will be paid back, and that the customer will be asked how. */
    public static Text paidOrderCancelled(RefundWords refund, ShopLines shop) {
        if (refund.moneyRecordCancelled() || refund.owed().signum() <= 0) {
            return new Text("Order " + refund.name() + " has been cancelled",
                    "Order " + refund.name() + " has been cancelled." + (refund.moneyRecordCancelled() ? ABOUT_ANY_MONEY : "") + line(shop.contactLine()));
        }
        if (refund.pieceUnavailable()) {
            return new Text("We are sorry: order " + refund.name() + " has been cancelled",
                    "We are sorry. A piece in order " + refund.name() + " is no longer available, so we have cancelled the order. We will pay you back "
                            + refund.whatIsPaidBack() + "." + refund.whoBearsTheCharge() + WE_WILL_BE_IN_TOUCH + line(shop.contactLine()));
        }
        return new Text("Order " + refund.name() + " has been cancelled",
                "Order " + refund.name() + " has been cancelled before it left us. We will pay you back " + refund.whatIsPaidBack() + "."
                        + refund.whoBearsTheCharge() + WE_WILL_BE_IN_TOUCH + line(shop.contactLine()));
    }

    /** Pieces came back after the parcel had gone (Phase 12): how many, what will be paid back, and that delivery is not. */
    public static Text returnReceived(RefundWords refund, ShopLines shop) {
        String received = "We have received " + refund.pieceCount() + " back from order " + refund.name() + ".";
        if (refund.moneyRecordCancelled() || refund.owed().signum() <= 0) {
            return new Text("We have received your return for order " + refund.name(),
                    received + (refund.moneyRecordCancelled() ? ABOUT_ANY_MONEY : "") + line(shop.contactLine()));
        }
        return new Text("We have received your return for order " + refund.name(),
                received + " We will pay you back " + Kwacha.write(refund.owed(), refund.currency()) + ". Delivery is not paid back."
                        + refund.whoBearsTheCharge() + line(shop.contactLine()));
    }

    /**
     * A refund was paid (Phase 12): what the customer received, and, when the charge for sending it was taken off, how that comes about.
     *
     * @param gross    what was owed and paid back
     * @param withheld the charge for sending it that the customer bore; zero when none was
     */
    public static Text refundPaid(UUID orderId, String receiptNumber, BigDecimal gross, BigDecimal withheld, String currency) {
        String order = OrderName.of(orderId, receiptNumber);
        String received = Kwacha.write(gross.subtract(withheld), currency);
        return new Text("Your refund for order " + order + " has been paid",
                withheld.signum() > 0
                        ? "We have paid back " + received + " for order " + order + ": " + Kwacha.write(gross, currency) + " less " + Kwacha.write(withheld, currency)
                                + " for the charge of sending it."
                        : "We have paid back " + received + " for order " + order + ".");
    }

    private static final java.time.format.DateTimeFormatter DAY = java.time.format.DateTimeFormatter.ofPattern("d MMMM", java.util.Locale.ENGLISH);

    /**
     * To staff who look after money (Phase 12): a refund has been owed and is not recorded as paid. Written for everyone who reads the money
     * list, so it says who acts. The amount is the one in the fact that raised it.
     */
    public static Text refundToPay(UUID orderId, String receiptNumber, BigDecimal owed, String currency, java.time.Instant owedSince, java.time.ZoneId zone) {
        String order = OrderName.of(orderId, receiptNumber);
        return new Text("A refund is waiting to be paid - order " + order,
                "A refund of " + Kwacha.write(owed, currency) + " for order " + order + " has been owed since " + DAY.format(owedSince.atZone(zone))
                        + " and is not recorded as paid. The owner pays it under " + Screens.REFUNDS_OWED + ".");
    }

    /** The words for a customer with an account: shown in the website's bell, and for some kinds also emailed. */
    public static Text forShopper(MessageKind kind, OrderWords order, ShopLines shop) {
        if (kind.aboutARefund()) {
            throw new IllegalArgumentException("the words about a refund carry figures a snapshot of the order does not have: they are written from the correction "
                    + "or the payout (paidOrderCancelled, returnReceived, refundPaid)");
        }
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
            case PAID_ORDER_CANCELLED, RETURN_RECEIVED, REFUND_PAID -> throw new IllegalStateException("refused above");
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

    /**
     * What an alert about recorded money says about it. The amount comes from payment's own announcement (the fact that raised the alert);
     * it is absent when only order's side of the story has been heard, and then no figure is printed.
     *
     * @param receiptNumber the order's receipt number when it is paid and notification has learnt it
     */
    public record MoneyWords(UUID orderId, String receiptNumber, BigDecimal gross, String currency, String method) {

        String order() {
            return OrderName.of(orderId, receiptNumber);
        }

        /** " of K180.00", or nothing when the amount is not known. */
        String amount() {
            return gross == null || currency == null ? "" : " of " + Kwacha.write(gross, currency);
        }

        /** " by MTN mobile money", or nothing when the method is not known. */
        String by() {
            return method == null || method.isBlank() ? "" : " by " + methodWords(method);
        }
    }

    /** A3: money was recorded and the sale has not completed. (The support line is added when the email is built.) */
    public static Text settlementStuck(MoneyWords money) {
        return new Text("A payment was recorded but the sale has not completed - order " + money.order(),
                "A payment" + money.amount() + money.by() + " was recorded for order " + money.order() + ", and the order is still not marked "
                        + "as paid. Please open " + Screens.SETTLEMENTS + ". If it says the sale is waiting for a box, receive that box into "
                        + "stock and the sale will finish by itself. If it says anything else, the system needs attention.");
    }

    /** A4: the reminder, after some hours and then daily. */
    public static Text settlementStillStuck(MoneyWords money) {
        return new Text("Still not completed - order " + money.order(),
                "The payment" + money.amount() + money.by() + " for order " + money.order() + " has still not completed the sale. The customer "
                        + "has paid and cannot be served until this is fixed. Please open " + Screens.SETTLEMENTS + ".");
    }

    /**
     * A5: what became of it.
     *
     * @param how             PAID, UNSETTLED or VOIDED
     * @param unsettledReason the code order or payment gave when the money could not pay the sale
     */
    public static Text settlementSorted(MoneyWords money, String how, String unsettledReason) {
        String body = switch (how) {
            case "PAID" -> "The payment" + money.amount() + " for order " + money.order() + " has now completed the sale. There is nothing "
                    + "more to do.";
            case "VOIDED" -> "The payment record" + money.amount() + " for order " + money.order() + " was cancelled by staff. There is "
                    + "nothing more to do.";
            default -> "The payment" + money.amount() + " for order " + money.order() + " could not pay the sale ("
                    + whyUnsettled(unsettledReason) + "). The money is being kept for the customer.";
        };
        return new Text("Sorted - order " + money.order(), body);
    }

    /** A11: the last note, when the alert closes by the clock with no answer ever heard. */
    public static Text settlementNeverAnswered(MoneyWords money) {
        return new Text("We have stopped reminding you - order " + money.order(),
                "We have stopped reminding you about the payment" + money.amount() + money.by() + " for order " + money.order() + ". We never "
                        + "heard that the sale was completed. Please open " + Screens.SETTLEMENTS + ".");
    }

    /**
     * Phase 13: a payment through ZynlePay that ZynlePay did not settle. It says what the payment is, why the system could not settle it, what
     * NOT to do, and what to do. It names no number and no reference: payment sends neither.
     *
     * @param reason why it is not settled: NO_ANSWER, AMOUNT_DIFFERS, PAID_AFTER_FAILED, SAME_MONEY_ON_RECORD or CONFIRMATION_VOIDED
     */
    public static Text providerPaymentUnresolved(MoneyWords money, String reason) {
        String why = switch (reason == null ? "" : reason) {
            case "AMOUNT_DIFFERS" -> "ZynlePay says it was paid, but for a different amount than was asked";
            case "PAID_AFTER_FAILED" -> "ZynlePay first said it had failed and now says it was paid";
            case "SAME_MONEY_ON_RECORD" -> "ZynlePay says it was paid, and the same money is already recorded as a payment keyed by hand";
            case "CONFIRMATION_VOIDED" -> "its payment record was cancelled, so it has to be decided again";
            default -> "ZynlePay has not said whether it was paid";
        };
        return new Text("A ZynlePay payment has not been settled - order " + money.order(),
                aOrAn(methodWords(money.method())) + " payment" + money.amount() + " for order " + money.order() + " was started through ZynlePay and "
                        + "has not been settled: " + why + ". The customer may have paid. Do not ask the customer to pay again, and do not confirm "
                        + "it by hand. Open " + Screens.PROVIDER_PAYMENTS + " and press Check now; if ZynlePay still does not settle it, the owner "
                        + "can mark it paid or not paid from ZynlePay's own records.");
    }

    /** "An Airtel Money", "An MTN mobile money" (said em-tee-en), "A Zamtel mobile money", "A card". */
    private static String aOrAn(String words) {
        boolean vowelSound = words.startsWith("MTN") || (!words.isEmpty() && "AEIOUaeiou".indexOf(words.charAt(0)) >= 0);
        return (vowelSound ? "An " : "A ") + words;
    }

    /** The reminder of {@link #providerPaymentUnresolved}, a day later. */
    public static Text providerPaymentUnresolvedReminder(MoneyWords money) {
        return new Text("A ZynlePay payment is still not settled - order " + money.order(),
                "The " + methodWords(money.method()) + " payment" + money.amount() + " for order " + money.order() + " through ZynlePay is still not "
                        + "settled. Open " + Screens.PROVIDER_PAYMENTS + ": the owner can mark it paid or not paid from ZynlePay's own records. Until "
                        + "then the order cannot be paid another way.");
    }

    /** A6: a payment record was cancelled, and the sale it paid had already gone through. */
    public static Text voidAfterSale(MoneyWords money) {
        return new Text("A cancelled payment record paid a sale anyway - order " + money.order(),
                "The payment record" + money.amount() + money.by() + " for order " + money.order() + " was cancelled (voided), but the sale had "
                        + "already gone through, so the order is marked as paid and its pieces are sold. Please check whether this money "
                        + "really arrived, and put it right with the customer and in your books.");
    }

    /**
     * A7b: one piece priced below cost, as it stands in the list of alerts (never emailed by itself). It names the piece and no figure:
     * what it cost and what it sells for stay on the Prices screen, behind the capability to see costs.
     *
     * @param piece the piece as a person names it, for example "Aurelia Solitaire Ring - Ring Size M (SKU RING-M)"
     */
    public static Text belowCostPiece(String piece) {
        return new Text(piece + " is priced below cost",
                piece + " is on sale for less than it cost you. Open " + Screens.BELOW_COST + ", to see what it cost and to change its price. "
                        + "If the price is like this on purpose, there is nothing to do.");
    }

    /**
     * A7: the email of one round, naming every piece newly below cost (at most {@code maxNamed}; the rest are counted).
     *
     * @param pieces the pieces as a person names them
     */
    public static Text belowCostDigest(java.util.List<String> pieces, int maxNamed) {
        boolean one = pieces.size() == 1;
        StringBuilder body = new StringBuilder(one ? "This piece is now on sale for less than it cost you:\n"
                : "These pieces are now on sale for less than they cost you:\n");
        pieces.stream().limit(maxNamed).forEach(piece -> body.append("- ").append(piece).append('\n'));
        if (pieces.size() > maxNamed) {
            body.append("and ").append(pieces.size() - maxNamed).append(" more\n");
        }
        body.append(one ? "Open " + Screens.BELOW_COST + ", to see what it cost and to change its price. If the price is like this on purpose, "
                + "there is nothing to do."
                : "Open " + Screens.BELOW_COST + ", to see what each one cost and to change its price. If a price is like this on purpose, there "
                + "is nothing to do.");
        return new Text(one ? "1 piece is now priced below cost" : pieces.size() + " pieces are now priced below cost", body.toString());
    }

    /** A8: the below-cost check has had no good round for a while. (The support line is added when the email is built.) */
    public static Text belowCostCheckDown(java.time.Instant since, java.time.ZoneId zone) {
        return new Text("The below-cost check is not running",
                "The system has not been able to compare your prices with your costs since " + LusakaTime.write(since, zone) + ". Until this "
                        + "is fixed you will not be told when a piece falls below cost. You can still open " + Screens.BELOW_COST + ", yourself.");
    }

    /** Why money could not pay a sale, in words. A code nobody taught this service points at the screen instead of guessing. */
    static String whyUnsettled(String reason) {
        if (reason == null) {
            return "see " + Screens.SETTLEMENTS;
        }
        return switch (reason) {
            case "WRONG_AMOUNT" -> "the amount was not the order's total";
            case "ORDER_CANCELLED" -> "the order had been cancelled";
            case "PIECE_SOLD" -> "a piece had already been sold";
            case "ALREADY_PAID" -> "the order was already paid";
            case "ORDER_UNKNOWN" -> "no such order was found";
            case "WRONG_DATE" -> "a paper sale was paid on a different day than it happened";
            default -> "see " + Screens.SETTLEMENTS;
        };
    }

    /** The line added to an alert that says the system needs attention, when the owner has not said who to turn to. */
    public static final String NOBODY_NAMED_FOR_SUPPORT = "Please contact the person who looks after your system.";

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
            case "CARD" -> "card";
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

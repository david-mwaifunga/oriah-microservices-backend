package com.oriah.notification.text;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.oriah.notification.text.MessageTexts.Text;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every alert to staff, word for word. An alert's subject is its one line in a list; its body is the whole email.
 *
 * <p>Rules: plain letters; no name, phone, address or payment reference; an amount only from the fact that raised the alert (a payment claim
 * carries none, so its alert names none: staff read the amount on the Awaiting payment list); the client's own rule on rejecting a claim, in
 * her words, wherever a claim is mentioned; and the three screen names used are the constants in {@link Screens}.
 */
class StaffTextsTest {

    private static final UUID ORDER = UUID.fromString("8a1f4b2e-3c5d-4e6f-9a0b-1c2d3e4f5a6b");
    private static final ZoneId LUSAKA = ZoneId.of("Africa/Lusaka");
    /** 16:05 on Tuesday 17 November in Lusaka. */
    private static final Instant HOLD_ENDS = Instant.parse("2026-11-17T14:05:00Z");

    @Test
    @DisplayName("A1, a customer says they have paid: how, where to look, until when the pieces are held, and when a claim may be rejected")
    void aCustomerSaysTheyHavePaid() {
        Text text = MessageTexts.paymentToConfirm(ORDER, "MTN", HOLD_ENDS, LUSAKA);

        assertThat(text.subject()).isEqualTo("A customer says they have paid - order 8A1F4B2E");
        assertThat(text.body()).isEqualTo("A customer says they have paid for order 8A1F4B2E by MTN mobile money. Please check that the money "
                + "has arrived: the Awaiting payment list shows the amount to look for and the reference the customer gave. When you find it, "
                + "confirm it there. If the money has not shown yet, do nothing: the pieces stay held for this customer until 16:05 on Tuesday "
                + "17 November. Reject the payment only if the provider tells you there is no such payment, or the reference has already paid "
                + "another order.");
    }

    @Test
    @DisplayName("A2, the reminder before the pieces are released")
    void stillWaiting() {
        Text text = MessageTexts.paymentToConfirmReminder(ORDER, "BANK_TRANSFER", HOLD_ENDS, LUSAKA);

        assertThat(text.subject()).isEqualTo("Still waiting - order 8A1F4B2E will be released at 16:05 on Tuesday 17 November");
        assertThat(text.body()).isEqualTo("Order 8A1F4B2E: the customer told us they paid by bank transfer, and the order is still not marked "
                + "as paid. The pieces will be released at 16:05 on Tuesday 17 November. If the money has arrived, please confirm it now on the "
                + "Awaiting payment list. If it has not shown, there is nothing to do: the order will close by itself and the customer will "
                + "see that on the website. Reject the payment only if the provider tells you there is no such payment, or the reference has "
                + "already paid another order.");
    }

    @Test
    @DisplayName("how a payment method is written")
    void theMethodInWords() {
        assertThat(MessageTexts.methodWords("MTN")).isEqualTo("MTN mobile money");
        assertThat(MessageTexts.methodWords("AIRTEL")).isEqualTo("Airtel Money");
        assertThat(MessageTexts.methodWords("ZAMTEL")).isEqualTo("Zamtel mobile money");
        assertThat(MessageTexts.methodWords("BANK_TRANSFER")).isEqualTo("bank transfer");
        assertThat(MessageTexts.methodWords("CASH")).isEqualTo("cash");
        assertThat(MessageTexts.methodWords("SOMETHING_NEW")).isEqualTo("SOMETHING_NEW");
        assertThat(MessageTexts.methodWords(null)).isEqualTo("a method we do not know");
    }

    @Test
    @DisplayName("A10, too many payment claims in a day to email each one")
    void manyClaims() {
        Text text = MessageTexts.claimEmailLimit(50);

        assertThat(text.subject()).isEqualTo("Many customers say they have paid - we have stopped emailing each one");
        assertThat(text.body()).isEqualTo("More than 50 customers said in the last day that they have paid. We have stopped emailing you about "
                + "each new one for now. Please open the Awaiting payment list: every one is there. Alerts about other things are still emailed.");
    }

    @Test
    @DisplayName("A9 and A9b, the two alerts about email itself (listed, never emailed)")
    void emailsAreNotGoingOut() {
        Text down = MessageTexts.emailDown(Instant.parse("2026-11-17T12:05:00Z"), LUSAKA);
        assertThat(down.subject()).isEqualTo("Emails are not going out");
        assertThat(down.body()).isEqualTo("Emails to customers and to you have not been going out since 14:05 on Tuesday 17 November. Messages "
                + "are kept and will be sent when email works again, unless they become too old. Alerts that could not be emailed will be "
                + "emailed then.");

        Text refused = MessageTexts.alertMailboxRefused();
        assertThat(refused.subject()).isEqualTo("An alert email address is being refused");
        assertThat(refused.body()).isEqualTo("The mail server refused an alert email to one of the alert addresses you gave us. Please check "
                + "those addresses. Until this is fixed, alerts may not reach that address by email; they are still on this list.");
    }

    @Test
    @DisplayName("a payment claim's alert names no amount, and no alert tells staff to reject because the money cannot be found")
    void noAmountAndNoRejectingWithoutProof() {
        List<Text> claimTexts = List.of(MessageTexts.paymentToConfirm(ORDER, "MTN", HOLD_ENDS, LUSAKA),
                MessageTexts.paymentToConfirmReminder(ORDER, "MTN", HOLD_ENDS, LUSAKA));
        for (Text text : claimTexts) {
            String all = text.subject() + " " + text.body();
            assertThat(all).doesNotContainPattern("K\\d").doesNotContain("cannot find").doesNotContain("can't find")
                    .contains("only if the provider tells you");
        }
    }

    @Test
    @DisplayName("every alert is in plain letters, names the order by its code, and uses the screen names from one place")
    void plainLettersAndOneSetOfScreenNames() {
        List<Text> all = List.of(MessageTexts.paymentToConfirm(ORDER, "MTN", HOLD_ENDS, LUSAKA),
                MessageTexts.paymentToConfirmReminder(ORDER, "MTN", HOLD_ENDS, LUSAKA), MessageTexts.claimEmailLimit(50),
                MessageTexts.emailDown(HOLD_ENDS, LUSAKA), MessageTexts.alertMailboxRefused());
        for (Text text : all) {
            assertThat(text.subject() + text.body()).matches("\\p{ASCII}+").doesNotContain(ORDER.toString());
        }
        assertThat(Screens.AWAITING_PAYMENT).isEqualTo("the Awaiting payment list");
        assertThat(Screens.SETTLEMENTS).isEqualTo("the Settlements list");
        assertThat(Screens.BELOW_COST).isEqualTo("Prices, Below cost");
        assertThat(MessageTexts.paymentToConfirm(ORDER, "MTN", HOLD_ENDS, LUSAKA).body()).contains(Screens.AWAITING_PAYMENT);
    }
}

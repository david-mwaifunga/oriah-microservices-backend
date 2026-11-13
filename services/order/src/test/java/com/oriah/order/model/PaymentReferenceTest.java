package com.oriah.order.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Order spells a claimed reference the way payment spells the reference staff type when they confirm the money: the two are compared byte for byte,
 * so any drift between them would make a real claim and its real payment look like strangers. These are the vectors payment's own
 * {@code ConfirmationRulesTest} pins, copied because services share no code but the shared modules; if one changes, both must.
 */
class PaymentReferenceTest {

    @Test
    @DisplayName("a reference is trimmed, upper-cased and stripped of every space, so 'mp 2411 abc' and ' MP2411ABC ' are one payment")
    void spellings() {
        assertThat(PaymentReference.normalise(" mp 2411 abc ")).isEqualTo("MP2411ABC");
        assertThat(PaymentReference.normalise("MP2411ABC")).isEqualTo("MP2411ABC");
        assertThat(PaymentReference.normalise("mp\t2411\n abc")).isEqualTo("MP2411ABC");
        assertThat(PaymentReference.normalise("mp.2411-ab/c_1")).isEqualTo("MP.2411-AB/C_1");
    }

    @Test
    @DisplayName("full-width letters and digits read as the ordinary ones")
    void lookalikes() {
        assertThat(PaymentReference.normalise("ＭＰ１２３")).isEqualTo("MP123");
    }

    @Test
    @DisplayName("upper-casing does not depend on the server's language: in Turkish an 'i' is still an 'I'")
    void theLocaleOfTheServerDoesNotMatter() {
        java.util.Locale before = java.util.Locale.getDefault();
        java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr-TR"));
        try {
            assertThat(PaymentReference.normalise("ref-iii")).isEqualTo("REF-III");
            assertThat(PaymentReference.normalise("refı")).as("a dotless i is an ordinary I").isEqualTo("REFI");
        } finally {
            java.util.Locale.setDefault(before);
        }
    }

    @Test
    @DisplayName("a reference that is empty, too short, too long or made of anything but letters, digits and . _ - / is refused")
    void refused() {
        assertThatThrownBy(() -> PaymentReference.normalise(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PaymentReference.normalise("   ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PaymentReference.normalise("ab")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("3");
        assertThatThrownBy(() -> PaymentReference.normalise("A".repeat(65))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("64");
        assertThatThrownBy(() -> PaymentReference.normalise("MP#123")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PaymentReference.normalise("MP​123")).as("a zero-width space is not a space and not a character")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PaymentReference.normalise("MP🙂123")).isInstanceOf(IllegalArgumentException.class);
        assertThat(PaymentReference.normalise("abc")).isEqualTo("ABC");
        assertThat(PaymentReference.normalise("A".repeat(64))).hasSize(64);
    }

    @Test
    @DisplayName("a claim names a method that leaves a reference: mobile money or a bank transfer, in any case; cash is not one")
    void methods() {
        assertThat(ClaimMethod.parse("mtn")).isEqualTo(ClaimMethod.MTN);
        assertThat(ClaimMethod.parse(" Airtel ")).isEqualTo(ClaimMethod.AIRTEL);
        assertThat(ClaimMethod.parse("ZAMTEL")).isEqualTo(ClaimMethod.ZAMTEL);
        assertThat(ClaimMethod.parse("bank_transfer")).isEqualTo(ClaimMethod.BANK_TRANSFER);
        assertThatThrownBy(() -> ClaimMethod.parse("CASH")).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("MTN");
        assertThatThrownBy(() -> ClaimMethod.parse(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ClaimMethod.parse("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the claim methods are exactly the ones payment accepts for a claim: its methods without cash")
    void theSameMethodsAsPayment() {
        // Payment's PaymentMethod is CASH, MTN, AIRTEL, ZAMTEL, BANK_TRANSFER and its claim refuses CASH (ClaimService.record).
        assertThat(java.util.Arrays.stream(ClaimMethod.values()).map(Enum::name)).containsExactly("MTN", "AIRTEL", "ZAMTEL", "BANK_TRANSFER");
    }
}

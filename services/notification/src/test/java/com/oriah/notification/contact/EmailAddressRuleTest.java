package com.oriah.notification.contact;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What counts as an address the shop may write to: exactly one, bare. Anything that a mail server could read as two recipients, or as a name with
 * an address, is not one. The rule is used wherever an address enters: a shopper's token, an alert list, and the mail transport itself.
 */
class EmailAddressRuleTest {

    @Test
    @DisplayName("one bare address passes, with the spaces around it taken off")
    void oneAddressPasses() {
        assertThat(EmailAddressRule.oneAddress("alice@oriah.local")).contains("alice@oriah.local");
        assertThat(EmailAddressRule.oneAddress("  first.last+tag@mail.example.co.zm ")).contains("first.last+tag@mail.example.co.zm");
    }

    @ParameterizedTest(name = "\"{0}\" is not one address")
    @ValueSource(strings = {
            "a@oriah.local, b@oriah.local", "a@oriah.local;b@oriah.local", "a@oriah.local b@oriah.local",
            "Alice <alice@oriah.local>", "<alice@oriah.local>", "alice@oriah.local.", "alice@", "@oriah.local", "alice",
            "alice@oriah", "alice@@oriah.local", "a lice@oriah.local", "alice@oriah.local\r\nBcc: x@y.zz", "", "   "})
    @DisplayName("two addresses, a display name, a trailing dot, a semicolon or a line break do not")
    void twoAddressesADisplayNameATrailingDotOrASemicolonDoNot(String text) {
        assertThat(EmailAddressRule.oneAddress(text)).isEmpty();
    }

    @Test
    @DisplayName("nothing at all is not an address")
    void nothingIsNotAnAddress() {
        assertThat(EmailAddressRule.oneAddress(null)).isEmpty();
    }

    @Test
    @DisplayName("an address longer than a mail server accepts is not one")
    void tooLongIsNotAnAddress() {
        assertThat(EmailAddressRule.oneAddress("a".repeat(250) + "@oriah.local")).isEmpty();
    }

    @Test
    @DisplayName("the domain of an address is read in small letters")
    void theDomainIsReadInSmallLetters() {
        assertThat(EmailAddressRule.domainOf("Alice@Oriah.LOCAL")).isEqualTo("oriah.local");
    }
}

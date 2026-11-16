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

    @ParameterizedTest(name = "\"{0}\" is not an address we send to")
    @ValueSource(strings = {
            // The mail library writes only the low byte of each character: U+012E leaves as '.', U+013E as '>', U+010D as a carriage return.
            "alice@oriah\u012Elocal.local", "victim@gmail.com\u013E.local", "alice@oriah.local\u010D\u010ABcc: x@y.zz", "al\u00EFce@oriah.local",
            "alice@\u043E\u0440\u0438\u0430\u0445.local", "alice\u0000x@oriah.local", "alice\u007F@oriah.local"})
    @DisplayName("an address with any character outside plain printable ASCII is refused: what the gate judged must be exactly what goes on the wire")
    void anAddressOutsidePlainAsciiIsRefused(String text) {
        assertThat(EmailAddressRule.oneAddress(text)).isEmpty();
    }

    @Test
    @DisplayName("what is returned is the cleaned address: a line break or a NUL around it is taken off like a space, never passed on")
    void whatSurroundsAnAddressIsTakenOff() {
        assertThat(EmailAddressRule.oneAddress("alice@oriah.local\r\n")).contains("alice@oriah.local");
        assertThat(EmailAddressRule.oneAddress("\u0000alice@oriah.local\u0000")).contains("alice@oriah.local");
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

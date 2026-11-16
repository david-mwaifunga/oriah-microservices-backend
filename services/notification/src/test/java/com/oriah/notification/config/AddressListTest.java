package com.oriah.notification.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The list of mailboxes an alert goes to, as the owner gives it in one line: addresses with commas between. It is read once, when the service
 * starts, by one rule. A mistake stops the start and says which entry is wrong by its place in the list, never by repeating it: the message of a
 * failed start is printed in a log.
 */
class AddressListTest {

    @Test
    @DisplayName("an address given twice is kept once, also when only the capitals of its domain differ")
    void anAddressListedTwiceIsKeptOnce() {
        assertThat(AddressList.parse("ORIAH_ALERT_EMAILS_MONEY", "owner@oriah.local,owner@oriah.local, owner@ORIAH.local"))
                .containsExactly("owner@oriah.local");
    }

    @Test
    @DisplayName("spaces and empty places between commas are dropped, and the order given is kept")
    void blanksAndSpacesAreDropped() {
        assertThat(AddressList.parse("ORIAH_ALERT_EMAILS_MONEY", " b@oriah.local , ,a@oriah.local,, "))
                .containsExactly("b@oriah.local", "a@oriah.local");
        assertThat(AddressList.parse("ORIAH_ALERT_EMAILS_COST", null)).isEmpty();
        assertThat(AddressList.parse("ORIAH_ALERT_EMAILS_COST", "  ")).isEmpty();
    }

    @Test
    @DisplayName("an entry that is not exactly one address is reported by the variable and its place, never by its text")
    void aBadEntryIsReportedByPosition() {
        assertThatThrownBy(() -> AddressList.parse("ORIAH_ALERT_EMAILS_MONEY", "owner@oriah.local, Mary <mary@secret.example>"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ORIAH_ALERT_EMAILS_MONEY")
                .hasMessageContaining("entry 2")
                .hasMessageNotContaining("mary")
                .hasMessageNotContaining("secret");
    }
}

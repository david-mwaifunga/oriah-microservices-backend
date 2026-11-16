package com.oriah.notification.delivery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A stack that is not production must be unable to reach a real mailbox. The second of its two locks: whatever mail server it talks to, only an
 * address on a name that can never be a real mailbox is handed over. Development and test data hold real-looking addresses and phone numbers.
 */
class SandboxGateTest {

    @ParameterizedTest(name = "{0} may be handed over")
    @ValueSource(strings = {"alice@oriah.local", "alerts-money@oriah.local", "x@dev.local", "x@shop.test", "x@a.example", "x@a.invalid",
            "x@box.localhost", "X@ORIAH.LOCAL"})
    @DisplayName("while not live, only an address on a name reserved for testing passes")
    void notLiveOnlyReservedDomainsPass(String address) {
        assertThat(SandboxGate.mayLeaveASandbox(address)).isTrue();
    }

    @ParameterizedTest(name = "{0} is never handed over")
    @ValueSource(strings = {"someone@example.com", "someone@gmail.com", "owner@oriah.co.zm", "x@local", "x@notlocal", "x@oriah.local.com",
            "x@test", "not-an-address", ""})
    @DisplayName("a real-looking address does not, and neither does anything that is not an address")
    void aRealLookingAddressDoesNotPass(String address) {
        assertThat(SandboxGate.mayLeaveASandbox(address)).isFalse();
    }

    @Test
    @DisplayName("nothing at all does not pass")
    void nothingDoesNotPass() {
        assertThat(SandboxGate.mayLeaveASandbox(null)).isFalse();
    }
}

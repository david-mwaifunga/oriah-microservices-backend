package com.oriah.payment.provider;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.oriah.payment.confirmation.ReferenceNumber;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Our reference for one attempt: what ZynlePay is asked to collect under, what its callback names, and the reference of the confirmation the
 * money is recorded as. Random, so it cannot be guessed to aim a forged callback at a payment in flight and gives away no count of payments.
 */
class ProviderReferenceTest {

    @Test
    @DisplayName("OZ and 14 characters with no letter that reads as a digit, already in the one spelling a confirmation's reference is stored in")
    void itsShape() {
        for (int i = 0; i < 200; i++) {
            String reference = ProviderReference.mint();

            assertThat(reference).matches("^OZ[0-9A-HJKMNP-TV-Z]{14}$").hasSize(16);
            assertThat(ReferenceNumber.normalise(reference)).isEqualTo(reference);
            assertThat(ProviderReference.isOurs(reference)).isTrue();
        }
    }

    @Test
    @DisplayName("no two alike in ten thousand")
    void noTwoAlike() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            assertThat(seen.add(ProviderReference.mint())).isTrue();
        }
    }

    @Test
    @DisplayName("what is not ours is told apart before the database is asked")
    void whatIsNotOurs() {
        assertThat(ProviderReference.isOurs("MP241105.0915.A12345")).isFalse();
        assertThat(ProviderReference.isOurs("oz7k2m9q4t8x1b5d")).isFalse();
        assertThat(ProviderReference.isOurs("OZ7K2M9Q4T8X1B5")).isFalse();
        assertThat(ProviderReference.isOurs(null)).isFalse();
    }
}

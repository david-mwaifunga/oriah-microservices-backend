package com.oriah.procurement.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a supplier is, with no Spring and no database: every rule the aggregate
 * enforces itself, so a supplier that breaks one can never exist.
 *
 * <p>From CLIENT_DECISIONS 4.1, a supplier is recorded once with "name, country,
 * who you deal with there, how you pay them". "How you pay them" is a
 * description, not a payment record: there is no supplier-payments ledger
 * (Q1: no deposits or part-payments).
 */
class SupplierTest {

    private static Supplier supplier() {
        return new Supplier("Fycargo", "CN", "Mr Li", "WhatsApp +86 000", "Bank transfer, in full");
    }

    @Test
    @DisplayName("a new supplier is active, has an id of its own and no version yet")
    void aNewSupplier() {
        Supplier s = supplier();

        assertThat(s.id()).isNotNull();
        assertThat(s.isActive()).isTrue();
        assertThat(s.isDeleted()).isFalse();
        // Null until saved: a null version is how Spring Data tells a new
        // supplier from an existing one, so it inserts without a lookup first.
        assertThat(s.version()).isNull();
        assertThat(supplier().id()).isNotEqualTo(s.id());
    }

    @Test
    @DisplayName("keeps what it was given, with the name and country tidied")
    void keepsWhatItWasGiven() {
        Supplier s = new Supplier("  Mercury Freight  ", " ae ", " Sara ", " sara@example.test ", " Card ");

        assertThat(s.name()).isEqualTo("Mercury Freight");
        assertThat(s.country()).isEqualTo("AE");
        assertThat(s.contactName()).isEqualTo("Sara");
        assertThat(s.contactDetails()).isEqualTo("sara@example.test");
        assertThat(s.paymentNotes()).isEqualTo("Card");
    }

    @ParameterizedTest(name = "name {0}")
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t"})
    @DisplayName("a name is required")
    void aNameIsRequired(String name) {
        assertThatThrownBy(() -> new Supplier(name, "ZM", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    @DisplayName("a name is at most 120 characters, counted after trimming")
    void aNameHasALimit() {
        assertThat(new Supplier("x".repeat(120), "ZM", null, null, null).name()).hasSize(120);
        assertThat(new Supplier(" " + "x".repeat(120) + " ", "ZM", null, null, null).name()).hasSize(120);

        assertThatThrownBy(() -> new Supplier("x".repeat(121), "ZM", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name")
                .hasMessageContaining("120");
    }

    @ParameterizedTest(name = "country {0}")
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "UK", "XX", "ZZ", "ZMB", "Z", "Zambia", "1A"})
    @DisplayName("a country is required, and is an ISO 3166-1 alpha-2 code (UK is GB; XX and ZZ are not countries)")
    void aCountryIsAnIsoCode(String country) {
        assertThatThrownBy(() -> new Supplier("Fycargo", country, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("country");
    }

    @ParameterizedTest(name = "{0} is stored as {1}")
    @ValueSource(strings = {"ZM", "zm", " Zm "})
    @DisplayName("a country is stored in upper case")
    void aCountryIsStoredInUpperCase(String given) {
        assertThat(new Supplier("Fycargo", given, null, null, null).country()).isEqualTo("ZM");
    }

    @ParameterizedTest(name = "[{0}]")
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t\n"})
    @DisplayName("the contact and payment descriptions are optional: blank means none")
    void theDescriptionsAreOptional(String blank) {
        Supplier s = new Supplier("Fycargo", "CN", blank, blank, blank);

        assertThat(s.contactName()).isNull();
        assertThat(s.contactDetails()).isNull();
        assertThat(s.paymentNotes()).isNull();
    }

    @Test
    @DisplayName("the contact name is at most 120 characters, the contact details and payment notes 500")
    void theDescriptionsHaveLimits() {
        assertThat(new Supplier("A", "CN", "x".repeat(120), "y".repeat(500), "z".repeat(500)).paymentNotes())
                .hasSize(500);

        assertThatThrownBy(() -> new Supplier("A", "CN", "x".repeat(121), null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("contactName");
        assertThatThrownBy(() -> new Supplier("A", "CN", null, "y".repeat(501), null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("contactDetails");
        assertThatThrownBy(() -> new Supplier("A", "CN", null, null, "z".repeat(501)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("paymentNotes");
    }

    @Test
    @DisplayName("change() replaces all five descriptive fields under the same rules")
    void changeReplacesTheDescriptiveFields() {
        Supplier s = supplier();
        var id = s.id();

        s.change("Fycargo Guangzhou", "hk", null, "  ", "Cash on collection");

        assertThat(s.id()).isEqualTo(id);
        assertThat(s.name()).isEqualTo("Fycargo Guangzhou");
        assertThat(s.country()).isEqualTo("HK");
        assertThat(s.contactName()).isNull();
        assertThat(s.contactDetails()).isNull();
        assertThat(s.paymentNotes()).isEqualTo("Cash on collection");
    }

    @Test
    @DisplayName("a change that breaks a rule is refused and leaves the supplier exactly as it was")
    void aRefusedChangeChangesNothing() {
        Supplier s = supplier();

        assertThatThrownBy(() -> s.change("New name", "UK", "New contact", "New details", "New notes"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("country");

        assertThat(s.name()).isEqualTo("Fycargo");
        assertThat(s.country()).isEqualTo("CN");
        assertThat(s.contactName()).isEqualTo("Mr Li");
        assertThat(s.contactDetails()).isEqualTo("WhatsApp +86 000");
        assertThat(s.paymentNotes()).isEqualTo("Bank transfer, in full");
    }

    @Test
    @DisplayName("a supplier can be stopped and started again, and doing either twice changes nothing")
    void activeCanBeSwitched() {
        Supplier s = supplier();

        s.deactivate();
        s.deactivate();
        assertThat(s.isActive()).isFalse();

        s.activate();
        s.activate();
        assertThat(s.isActive()).isTrue();
    }

    @Test
    @DisplayName("stopping a supplier is not deleting it: the two are separate switches")
    void inactiveIsNotDeleted() {
        Supplier s = supplier();

        s.deactivate();
        assertThat(s.isDeleted()).isFalse();

        s.markDeleted();
        assertThat(s.isDeleted()).isTrue();
        assertThat(s.isActive()).isFalse();
    }
}

package com.oriah.quote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Who the parcel is for, and how to reach them (Phase 8, design D8). The packer or the driver needs a name and a phone number, and nothing in the
 * system captured either (a shopper's account has a name and an email; an address has neither a recipient nor a phone). So checkout asks for them
 * with every delivery, and this one rule, shared by checkout and order, says what a good one looks like and what it is kept as.
 *
 * <p>A phone number is kept in ONE spelling, {@code +<country code><number>} with no spaces: the same person typing {@code 097 123 4567} on one
 * order and {@code +260971234567} on another is one number, and a driver can tap it. Zambia is where the shop is, so a number typed the way
 * Zambians type theirs (a leading 0) is read as Zambian. Anything that cannot be read with confidence is refused with a plain message rather than
 * guessed at, because a wrong digit is a parcel nobody can deliver.
 */
class DeliveryContactTest {

    @ParameterizedTest(name = "[{0}] is kept as {1}")
    @CsvSource(delimiter = '|', value = {
            "0971234567              | +260971234567",
            "097 123 4567            | +260971234567",
            "097-123-4567            | +260971234567",
            "(097) 123 4567          | +260971234567",
            "097.123.4567            | +260971234567",
            "+260971234567           | +260971234567",
            "+260 97 123 4567        | +260971234567",
            "+260-97-123-4567        | +260971234567",
            "260971234567            | +260971234567",
            "260 97 123 4567         | +260971234567",
            "00260971234567          | +260971234567",
            "00 260 97 123 4567      | +260971234567",
            "0211123456              | +260211123456",
            "+2600971234567          | +260971234567",
            "+260 (0) 97 123 4567    | +260971234567",
            "260 0971234567          | +260971234567",
            "+44 7911 123456         | +447911123456",
            "0044 7911 123456        | +447911123456",
            "+1 (415) 555-2671       | +14155552671",
            "+27 82 123 4567         | +27821234567",
    })
    @DisplayName("a phone number is read the way Zambians type it, and kept in one spelling")
    void phoneIsKeptInOneSpelling(String typed, String kept) {
        assertThat(DeliveryContact.phone(typed)).isEqualTo(kept);
    }

    @Test
    @DisplayName("spaces, tabs and line breaks round a number are ignored (a number pasted from a message)")
    void surroundingWhitespaceIsIgnored() {
        assertThat(DeliveryContact.phone("  0971234567\t")).isEqualTo("+260971234567");
        assertThat(DeliveryContact.phone("\n+260971234567 \r\n")).isEqualTo("+260971234567");
        assertThat(DeliveryContact.phone("097 123 4567")).isEqualTo("+260971234567");
    }

    @ParameterizedTest(name = "[{0}] is refused")
    @ValueSource(strings = {
            "12345",                // far too short
            "+1234567",             // 7 digits: shorter than any number
            "+1234567890123456",    // 16 digits: longer than any number
            "971234567",            // nine digits and no leading 0 or country code: which country?
            "097123456",            // a Zambian number with one digit missing
            "09712345678",          // a Zambian number with one digit too many
            "+26097123456",         // +260 with eight digits after it
            "+2609712345678",       // +260 with ten digits after it
            "260971234",            // 260 and only six digits
            "+0971234567",          // a plus and then a trunk 0
            "abc",
            "097-ABC-4567",
            "0971234567 ext 5",
            "097 123 4567 or 096 111 2222",
            "++260971234567",
            "+260971234567+",
            "+26-097+1234567",
            "0971234567\u0000",
            "٠٩٧١٢٣٤٥٦٧", // Arabic-Indic digits: only 0 to 9 are digits here
            "+",
            "00",
            "0",
    })
    @DisplayName("a number that cannot be read with confidence is refused with a plain message, never guessed at")
    void badPhoneIsRefused(String typed) {
        assertThatThrownBy(() -> DeliveryContact.phone(typed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("phone");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t\n"})
    @DisplayName("a phone number is required")
    void phoneIsRequired(String typed) {
        assertThatThrownBy(() -> DeliveryContact.phone(typed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("phone number is required");
    }

    @Test
    @DisplayName("what is kept is always plus and 8 to 15 digits, the first not 0: the shape the database's CHECK holds")
    void whatIsKeptFitsTheDatabaseShape() {
        for (String typed : new String[] {"0971234567", "+447911123456", "+14155552671", "00 27 82 123 4567", "+260 (0) 211 123 456"}) {
            assertThat(DeliveryContact.phone(typed)).matches("^\\+[1-9][0-9]{7,14}$");
        }
    }

    @Test
    @DisplayName("a name is kept trimmed, with runs of spaces, tabs and line breaks made one space")
    void nameIsTidied() {
        assertThat(DeliveryContact.name("Mulenga Chanda")).isEqualTo("Mulenga Chanda");
        assertThat(DeliveryContact.name("  Mulenga \t  Chanda\n")).isEqualTo("Mulenga Chanda");
        assertThat(DeliveryContact.name("Mulenga Chanda")).isEqualTo("Mulenga Chanda");
    }

    @Test
    @DisplayName("names from every language the shop's customers write in are kept as typed")
    void namesAreKeptAsTyped() {
        assertThat(DeliveryContact.name("Chilufya Mwansa-Phiri")).isEqualTo("Chilufya Mwansa-Phiri");
        assertThat(DeliveryContact.name("Ng'andu O'Brien")).isEqualTo("Ng'andu O'Brien");
        assertThat(DeliveryContact.name("Zoë Ngúlube")).isEqualTo("Zoë Ngúlube");
        assertThat(DeliveryContact.name("Mrs. B. Banda (Lusaka Hospital)")).isEqualTo("Mrs. B. Banda (Lusaka Hospital)");
        assertThat(DeliveryContact.name("Li")).isEqualTo("Li");
        assertThat(DeliveryContact.name("x".repeat(120))).hasSize(120);
    }

    @ParameterizedTest(name = "[{0}] is refused")
    @ValueSource(strings = {"", " ", "A", "  B  ", "12345", "!!!", "-", "Mulenga\u0000Chanda", "Mulenga\u0007", "Mulenga ‮Chanda"})
    @DisplayName("a name needs two characters with a letter among them, and no control or direction-changing characters")
    void badNameIsRefused(String typed) {
        assertThatThrownBy(() -> DeliveryContact.name(typed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    @DisplayName("a name is required, and 120 characters is the most")
    void nameIsRequiredAndBounded() {
        assertThatThrownBy(() -> DeliveryContact.name(null)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("name is required");
        assertThatThrownBy(() -> DeliveryContact.name("x".repeat(121))).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("120");
    }
}

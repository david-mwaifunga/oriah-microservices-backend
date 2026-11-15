package com.oriah.finance.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.oriah.finance.exception.FinanceProblem;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A phone number is refused however it is dressed; a reference that merely has many digits is not a phone number. */
class FreeTextTest {

    @Test
    @DisplayName("a Zambian phone number is refused in every way it is typed")
    void phonesAreRefused() {
        for (String phone : new String[] {"0977123456", "097 712 3456", "0977-123-456", "0977.123.456", "(0977) 123 456", "+260 977 123 456", "+260977123456", "00260977123456".substring(2),
                "260977123456", "call 0966 000 222 please", "\u0660\u0669\u0667\u0667\u0661\u0662\u0663\u0664\u0665\u0666"}) {
            assertThatThrownBy(() -> FreeText.noPhoneNumbers(phone, "the note")).as(phone).isInstanceOf(FinanceProblem.class);
        }
    }

    @Test
    @DisplayName("a long reference that is not a phone number is accepted")
    void referencesAreNotPhones() {
        for (String reference : new String[] {"123456789", "1234567890123456", "ZRA-2026-000123456", "TPIN 1002345678", "INV 20261014 0042"}) {
            assertThatCode(() -> FreeText.noPhoneNumbers(reference, "the reference")).as(reference).doesNotThrowAnyException();
        }
    }
}

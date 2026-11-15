package com.oriah.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;

import com.oriah.order.exception.SalesProblem;

/**
 * The fare rule (Phase 8, design D6): when a courier fare is due, and what a packer may type. A plain unit test: no Spring, no database, so every
 * option and payer is tried and a mutant of the rule dies in a second.
 *
 * <p>What the client decided (CLIENT_DECISIONS Q23 to Q27): the fare is a cost of the month "except where the recipient paid"; own delivery is
 * income, and its fuel and driver pay are period expenses, never a fare; self-collect has no courier. The rule is keyed on the option SOLD and who
 * pays, never on the delivery charge (which is K0.00 for a free-delivery order that Oriah still pays the courier for).
 */
class FareRuleTest {

    private static final BigDecimal CEILING = new BigDecimal("5000.00");

    @ParameterizedTest(name = "{0} paid by {1}: fare due = {2}")
    @CsvSource({
            "YANGO,           ORIAH_COLLECTS, true",
            "PLATINUM,        ORIAH_COLLECTS, true",
            "EMS,             ORIAH_COLLECTS, true",
            "POWER_TOURS_BUS, ORIAH_COLLECTS, true",
            "YANGO,           RECIPIENT_PAYS, false",
            "PLATINUM,        RECIPIENT_PAYS, false",
            "POWER_TOURS_BUS, RECIPIENT_PAYS, false",
            "OWN_DELIVERY,    ORIAH_COLLECTS, false",
            "OWN_DELIVERY,    RECIPIENT_PAYS, false",
            "SELF_COLLECT,    ,               false",
            "SELF_COLLECT,    ORIAH_COLLECTS, false",
    })
    @DisplayName("a fare is due only when Oriah pays a courier that is neither our own driver nor self-collect")
    void whenIsAFareDue(String option, String payer, boolean due) {
        assertThat(FareRule.due(option, payer)).isEqualTo(due);
    }

    @Test
    @DisplayName("an order that knows no option or no payer (placed before Phase 7) never has a fare due")
    void aLegacyOrderHasNoFareDue() {
        assertThat(FareRule.due(null, null)).isFalse();
        assertThat(FareRule.due("YANGO", null)).isFalse();
        assertThat(FareRule.due(null, "ORIAH_COLLECTS")).isFalse();
    }

    @Test
    @DisplayName("a due fare is kept at two decimals, and a courier that charged nothing is a real answer: 0.00")
    void aDueFareIsKept() {
        assertThat(FareRule.check("YANGO", "ORIAH_COLLECTS", new BigDecimal("100"), CEILING)).isEqualByComparingTo("100.00");
        assertThat(FareRule.check("YANGO", "ORIAH_COLLECTS", new BigDecimal("100"), CEILING).scale()).isEqualTo(2);
        assertThat(FareRule.check("PLATINUM", "ORIAH_COLLECTS", new BigDecimal("87.5"), CEILING)).isEqualByComparingTo("87.50");
        assertThat(FareRule.check("PLATINUM", "ORIAH_COLLECTS", new BigDecimal("0.00"), CEILING)).isEqualByComparingTo("0.00");
        assertThat(FareRule.check("PLATINUM", "ORIAH_COLLECTS", new BigDecimal("0"), CEILING)).isNotNull();
        assertThat(FareRule.check("PLATINUM", "ORIAH_COLLECTS", CEILING, CEILING)).isEqualByComparingTo("5000.00");
    }

    @Test
    @DisplayName("a due fare that was not typed is 409 fare-required")
    void aDueFareMustBeTyped() {
        assertThatThrownBy(() -> FareRule.check("YANGO", "ORIAH_COLLECTS", null, CEILING))
                .isInstanceOfSatisfying(SalesProblem.class, p -> {
                    assertThat(p.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(p.code()).isEqualTo("fare-required");
                });
    }

    @ParameterizedTest(name = "{0} paid by {1}: a typed fare is refused")
    @CsvSource({
            "YANGO,           RECIPIENT_PAYS",
            "PLATINUM,        RECIPIENT_PAYS",
            "OWN_DELIVERY,    ORIAH_COLLECTS",
            "OWN_DELIVERY,    RECIPIENT_PAYS",
            "SELF_COLLECT,    ORIAH_COLLECTS",
    })
    @DisplayName("a fare typed where none is due is 409 no-fare-for-this-delivery, even 0.00: the recipient's money, or our own driver's, is not a fare")
    void noFareWhereNoneIsDue(String option, String payer) {
        for (String typed : new String[] {"0.00", "100.00"}) {
            assertThatThrownBy(() -> FareRule.check(option, payer, new BigDecimal(typed), CEILING))
                    .isInstanceOfSatisfying(SalesProblem.class, p -> {
                        assertThat(p.status()).isEqualTo(HttpStatus.CONFLICT);
                        assertThat(p.code()).isEqualTo("no-fare-for-this-delivery");
                    });
        }
    }

    @Test
    @DisplayName("where no fare is due and none is typed there is nothing to keep (null)")
    void nothingToKeepWhenNoneIsDue() {
        assertThat(FareRule.check("YANGO", "RECIPIENT_PAYS", null, CEILING)).isNull();
        assertThat(FareRule.check("OWN_DELIVERY", "ORIAH_COLLECTS", null, CEILING)).isNull();
        assertThat(FareRule.check("SELF_COLLECT", null, null, CEILING)).isNull();
    }

    @ParameterizedTest(name = "{0} is refused as 400 invalid-fare")
    @CsvSource({"-0.01", "-100", "5000.01", "999999", "100.001", "0.005"})
    @DisplayName("a fare below nothing, above the ceiling (a typo guard) or finer than one ngwee is 400 invalid-fare")
    void aFareOutOfRangeIsRefused(String typed) {
        assertThatThrownBy(() -> FareRule.check("YANGO", "ORIAH_COLLECTS", new BigDecimal(typed), CEILING))
                .isInstanceOfSatisfying(SalesProblem.class, p -> {
                    assertThat(p.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(p.code()).isEqualTo("invalid-fare");
                });
    }

    @Test
    @DisplayName("the ceiling is a setting: a bigger one admits a bigger fare, a smaller one refuses it")
    void theCeilingIsASetting() {
        assertThat(FareRule.check("YANGO", "ORIAH_COLLECTS", new BigDecimal("6000.00"), new BigDecimal("10000.00"))).isEqualByComparingTo("6000.00");
        assertThatThrownBy(() -> FareRule.check("YANGO", "ORIAH_COLLECTS", new BigDecimal("500.01"), new BigDecimal("500.00")))
                .isInstanceOfSatisfying(SalesProblem.class, p -> assertThat(p.code()).isEqualTo("invalid-fare"));
    }
}

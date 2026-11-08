package com.oriah.money;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The guard must find a money field without the rule -- including one on a
 * line nested inside a request -- and must not complain about responses.
 * Without this test a guard that scans nothing would pass every service.
 */
class MoneyEdgeGuardTest {

    @Test
    @DisplayName("finds unguarded money on a request and on a line nested in one; ignores responses")
    void findsUnguardedMoney() {
        List<String> unguarded = MoneyEdgeGuard.unguardedMoneyFields("com.oriah.money.fixture");

        assertThat(unguarded).containsExactlyInAnyOrder(
                "com.oriah.money.fixture.LooseOrderRequest.discount",
                "com.oriah.money.fixture.LooseOrderRequest$Line.unitPrice");
        assertThat(MoneyEdgeGuard.requestRecordCount("com.oriah.money.fixture")).isEqualTo(3);
    }
}

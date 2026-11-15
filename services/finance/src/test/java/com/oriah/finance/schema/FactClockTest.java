package com.oriah.finance.schema;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import com.oriah.finance.TestcontainersConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "As of T" is cut on finance's own clock, taken AFTER the ledger lock is held (clock_timestamp()), never on the moment a transaction began (now()). A sale fact or a statement
 * check stamped with now() could carry a time before the moment a report was issued although it was written after it, so the same report asked for again at that moment would
 * count it while the stored one did not.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
class FactClockTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("every table a report cuts by time is stamped with the clock at the insert, not the start of the transaction")
    void stampedAtTheInsert() {
        for (String[] column : new String[][] {{"ledger_entry", "recorded_at"}, {"sale", "created_at"}, {"statement_check", "entered_at"}}) {
            String dflt = jdbc.queryForObject("SELECT column_default FROM information_schema.columns WHERE table_name = ? AND column_name = ?", String.class, column[0], column[1]);
            assertThat(dflt).as(column[0] + "." + column[1]).contains("clock_timestamp");
        }
    }
}

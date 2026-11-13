package com.oriah.inventory.events;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.oriah.events.OutboxSchemaGuard;
import com.oriah.inventory.TestcontainersConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Inventory publishes facts since Phase 7 (a reservation that expired, a reservation that was consumed, a box that was swapped), so V11
 * gave it the event log's outbox, copied from the reference SQL in shared/oriah-events. The module's guard proves the copy exact (every
 * column, constraint and trigger) and that the outbox really refuses UPDATE, DELETE and TRUNCATE: it is inventory's permanent record of
 * what happened to stock, and a row that could be edited would make a replay lie.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@TestPropertySource(properties = {
        "eureka.client.enabled=false"
})
class OutboxSchemaTest {

    @Autowired
    private DataSource dataSource;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("the outbox and its relay cursor are exactly the reference tables, the cursor seeded at 0")
    void outboxMatchesTheReference() {
        assertThat(OutboxSchemaGuard.problems(dataSource)).isEmpty();
    }
}

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

import com.oriah.events.InboxSchemaGuard;
import com.oriah.inventory.TestcontainersConfiguration;

import static com.oriah.events.InboxSchemaGuard.Table.PROCESSED_AGGREGATE;
import static com.oriah.events.InboxSchemaGuard.Table.PROCESSED_EVENT;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * This service's migration created the inbox tables exactly as the event log
 * convention agreed (shared/oriah-events, db/oriah-events/V2 and V3).
 *
 * <p>Inventory's product_index is a projection -- a copy of catalog's
 * variants -- so it keeps both: processed_event, the append-only record of
 * each event applied, and processed_aggregate, the newest seq applied per
 * product, so an older snapshot arriving late never overwrites a newer one.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@TestPropertySource(properties = {
        "eureka.client.enabled=false",
        "spring.cloud.function.definition=productEvents",
        "spring.cloud.stream.bindings.productEvents-in-0.destination=catalog.product"
})
class InboxSchemaTest {

    @Autowired
    private DataSource dataSource;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("processed_event and processed_aggregate are exactly the agreed shape; processed_event is append-only")
    void inboxTablesAreTheAgreedShape() {
        assertThat(InboxSchemaGuard.problems(dataSource, PROCESSED_EVENT, PROCESSED_AGGREGATE)).isEmpty();
    }
}

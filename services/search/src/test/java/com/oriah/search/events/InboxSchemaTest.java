package com.oriah.search.events;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import com.oriah.events.InboxSchemaGuard;
import com.oriah.search.TestcontainersConfiguration;

import static com.oriah.events.InboxSchemaGuard.Table.PROCESSED_AGGREGATE;
import static com.oriah.events.InboxSchemaGuard.Table.PROCESSED_EVENT;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * This service's migration created the inbox tables exactly as the event log
 * convention agreed (shared/oriah-events, db/oriah-events/V2 and V3).
 *
 * <p>Search is a projection -- a copy of catalog's products -- so it keeps
 * both: processed_event, the append-only record of each event applied, and
 * processed_aggregate, the newest seq applied per product, which outlives a
 * deleted product's own row and stops a late, older snapshot bringing it back.
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

    @Test
    @DisplayName("processed_event and processed_aggregate are exactly the agreed shape; processed_event is append-only")
    void inboxTablesAreTheAgreedShape() {
        assertThat(InboxSchemaGuard.problems(dataSource, PROCESSED_EVENT, PROCESSED_AGGREGATE)).isEmpty();
    }
}

package com.oriah.finance.events;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.oriah.finance.TestcontainersConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The facts that are never money, kept for the report: the consignment lines procurement announced (the standing list of estimated opening stock reads them), the names of
 * products (display only: the report by product never reads a name for a figure), and the orders finance has heard of (a typed expense that names one is checked
 * against them).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
class FactApplierTest {

    @Autowired
    private FactApplier facts;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("a received consignment line is kept as revision 1 and a correction as the next, each whole; whether its supplier is known and whether it is an estimate are kept")
    void consignmentLines() {
        UUID consignment = UUID.randomUUID();
        UUID line = UUID.randomUUID();

        facts.consignmentLine(Events.lineContext("consignment.received", line, 1), Events.consignmentLine(consignment, line, null, true, "10702.90", false));
        facts.consignmentLine(Events.lineContext("consignment.amended", line, 2), Events.consignmentLine(consignment, line, 2, true, "10520.29", false));
        facts.consignmentLine(Events.lineContext("consignment.amended", line, 2), Events.consignmentLine(consignment, line, 2, true, "10520.29", false));

        var rows = jdbc.queryForList("SELECT revision, landed_total, is_estimated, supplier_known, kind, quantity FROM consignment_line_fact WHERE consignment_line_id = ? ORDER BY revision", line);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("revision")).isEqualTo(1);
        assertThat((BigDecimal) rows.get(0).get("landed_total")).isEqualByComparingTo("10702.90");
        assertThat(rows.get(0).get("is_estimated")).isEqualTo(true);
        assertThat(rows.get(0).get("supplier_known")).as("day-one stock has no supplier").isEqualTo(false);
        assertThat(rows.get(1).get("revision")).isEqualTo(2);
        assertThat((BigDecimal) rows.get(1).get("landed_total")).isEqualByComparingTo("10520.29");
    }

    @Test
    @DisplayName("a product's name follows its newest snapshot, an older one arriving late is ignored, and a deleted product is marked, never removed")
    void productNames() {
        UUID product = UUID.randomUUID();

        facts.product(Events.productContext("product.created", product, 1), Events.product(product, "Aurelia Ring", "SELLABLE"));
        facts.product(Events.productContext("product.updated", product, 3), Events.product(product, "Aurelia Ring, gold", "SELLABLE"));
        facts.product(Events.productContext("product.updated", product, 2), Events.product(product, "an older name", "SELLABLE"));

        assertThat(jdbc.queryForObject("SELECT title FROM product_name WHERE product_id = ?", String.class, product)).isEqualTo("Aurelia Ring, gold");

        facts.product(Events.productContext("product.deleted", product, 4), Events.productDeleted(product));

        assertThat(jdbc.queryForObject("SELECT deleted FROM product_name WHERE product_id = ?", Boolean.class, product)).isTrue();
        assertThat(jdbc.queryForObject("SELECT title FROM product_name WHERE product_id = ?", String.class, product)).as("a deleted product keeps its name for old months").isEqualTo("Aurelia Ring, gold");
    }

    @Test
    @DisplayName("a snapshot with no kind is a sellable product, as everything before kinds existed was")
    void anAbsentKind() {
        UUID product = UUID.randomUUID();

        facts.product(Events.productContext("product.created", product, 1), Events.product(product, "Old Ring", null));

        assertThat(jdbc.queryForObject("SELECT kind FROM product_name WHERE product_id = ?", String.class, product)).isEqualTo("SELLABLE");
    }

    @Test
    @DisplayName("an order that was created is known, with its channel, and nothing about its owner is kept")
    void orders() {
        UUID web = UUID.randomUUID();
        UUID phone = UUID.randomUUID();

        facts.orderCreated(Events.ctx("order.created", "order", web, java.time.Instant.parse("2026-10-14T08:00:00Z")), Events.orderCreated(web, null));
        facts.orderCreated(Events.ctx("order.created", "order", phone, java.time.Instant.parse("2026-10-14T08:00:00Z")), Events.orderCreated(phone, "PHONE"));
        facts.orderCreated(Events.ctx("order.created", "order", phone, java.time.Instant.parse("2026-10-14T08:00:00Z")), Events.orderCreated(phone, "PHONE"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_ref WHERE order_id = ?", Long.class, web)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT channel FROM order_ref WHERE order_id = ?", String.class, phone)).isEqualTo("PHONE");
        assertThat(String.join("", jdbc.queryForList("SELECT row_to_json(t)::text FROM order_ref t", String.class))).doesNotContain("kc-alice-0001");
    }
}

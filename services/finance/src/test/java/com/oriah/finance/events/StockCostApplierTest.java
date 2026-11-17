package com.oriah.finance.events;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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
import com.oriah.finance.events.Events.Stock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What every row of the stock ledger cost reaches profit through here and nowhere else: a sale's cost of goods (or of packaging), stock written off or given away at its
 * cost, a take-back (netting inside the reason it takes back), and a cost correction (the old row reversed and the new one written, each on the day of its own sale, so
 * every month moves by exactly its own units' corrected cost). The product is the one the event names, frozen on the row; nothing guesses a product.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
class StockCostApplierTest {

    @Autowired
    private StockCostApplier stock;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private long ids = 5_000_000 + (System.nanoTime() % 1_000_000) * 10;

    private Stock row() {
        return new Stock().movement(++ids);
    }

    private void apply(Stock s) {
        stock.apply(s.context(), s.body());
    }

    private Map<String, Object> entry(Stock s) {
        return jdbc.queryForMap("SELECT entry_key, account, reason, amount, effect_day, effect_month, product_id, stock_kind, estimated, movement_id, stock_action_id, sale_line_id, source, "
                + "consignment_line_id FROM ledger_entry WHERE movement_id = ?", s.movementId);
    }

    private BigDecimal month(UUID product, String account, String month) {
        return jdbc.queryForObject("SELECT coalesce(sum(amount), 0) FROM ledger_entry WHERE product_id = ? AND account = ? AND effect_month = ?::date", BigDecimal.class, product, account, month);
    }

    private boolean open(String code, String subject) {
        return "RAISE".equals(jdbc.query("SELECT action FROM anomaly_log WHERE code = ? AND subject = ? ORDER BY id DESC LIMIT 1", (rs, n) -> rs.getString(1), code, subject)
                .stream().findFirst().orElse(null));
    }

    @Test
    @DisplayName("a sale releases the cost of the pieces that left, on the day of the sale, in cost of goods, with the product and the lot's line frozen on the row")
    void aSale() {
        Stock s = row().sale("SELLABLE", -2, "1528.99");

        apply(s);

        Map<String, Object> e = entry(s);
        assertThat(e.get("account")).isEqualTo("COGS_GOODS");
        assertThat((BigDecimal) e.get("amount")).isEqualByComparingTo("3057.98");
        assertThat(((java.sql.Date) e.get("effect_day")).toLocalDate()).isEqualTo(LocalDate.parse("2026-10-10"));
        assertThat(e.get("product_id")).isEqualTo(s.productId);
        assertThat(e.get("stock_kind")).isEqualTo("SELLABLE");
        assertThat(e.get("estimated")).isEqualTo(false);
        assertThat(e.get("entry_key")).isEqualTo("mv:" + s.movementId);
        assertThat(e.get("source")).isEqualTo("STOCK_COST");
        assertThat(e.get("stock_action_id")).isEqualTo(s.actionId);
        assertThat(e.get("sale_line_id")).as("a sale's action is its sale line").isEqualTo(s.actionId);
        assertThat(e.get("consignment_line_id")).isNotNull();
    }

    @Test
    @DisplayName("packaging that went out with a sale is cost of packaging, against the product it went with")
    void packaging() {
        UUID ring = UUID.randomUUID();
        Stock s = row().sale("PACKAGING", -1, "78.99").product(ring);

        apply(s);

        assertThat(entry(s).get("account")).isEqualTo("COGS_PACKAGING");
        assertThat(entry(s).get("product_id")).isEqualTo(ring);
    }

    @Test
    @DisplayName("stock written off or given away is booked at its cost under the reason it left for, never as cost of goods")
    void writeOffsAndGiveAways() {
        for (String reason : List.of("WRITE_OFF_LOST", "WRITE_OFF_STOLEN", "WRITE_OFF_BROKEN", "GIVEN_AWAY_MODEL", "GIVEN_AWAY_INFLUENCER", "GIVEN_AWAY_GIFT", "GIVEN_AWAY_PRIZE")) {
            Stock s = row().writeOff(reason, -3, "10.00");

            apply(s);

            Map<String, Object> e = entry(s);
            assertThat(e.get("account")).as(reason).isEqualTo("STOCK_OUT");
            assertThat(e.get("reason")).isEqualTo(reason);
            assertThat((BigDecimal) e.get("amount")).isEqualByComparingTo("30.00");
        }
    }

    @Test
    @DisplayName("a take-back is booked inside the reason it takes back, as a negative, on its own day: the write-off line shrinks in the month it is taken back")
    void aTakeBack() {
        UUID product = UUID.randomUUID();
        apply(row().writeOff("WRITE_OFF_LOST", -2, "10.00").product(product).at("2026-10-10T10:00:00Z"));
        Stock back = row().takeBack("WRITE_OFF_LOST", 1, "10.00").product(product).at("2026-11-03T10:00:00Z");

        apply(back);

        Map<String, Object> e = entry(back);
        assertThat(e.get("account")).isEqualTo("STOCK_OUT");
        assertThat(e.get("reason")).isEqualTo("WRITE_OFF_LOST");
        assertThat((BigDecimal) e.get("amount")).isEqualByComparingTo("-10.00");
        assertThat(month(product, "STOCK_OUT", "2026-10-01")).isEqualByComparingTo("20.00");
        assertThat(month(product, "STOCK_OUT", "2026-11-01")).isEqualByComparingTo("-10.00");
    }

    @Test
    @DisplayName("a cost corrected after units were sold moves each month by exactly its own units: the old row is reversed and the new one written, both on the day of the sale")
    void aCostCorrection() {
        UUID product = UUID.randomUUID();
        UUID action = UUID.randomUUID();
        Stock original = row().action(action).product(product).sale("SELLABLE", -3, "1114.29").at("2026-10-10T10:00:00Z");
        Stock reversal = row().action(action).product(product).sale("SELLABLE", 3, "1114.29").role("RECUT_REVERSAL").at("2026-10-10T10:00:00Z");
        Stock replacement = row().action(action).product(product).sale("SELLABLE", -3, "1071.43").role("RECUT_REPLACEMENT").at("2026-10-10T10:00:00Z");

        apply(original);
        assertThat(month(product, "COGS_GOODS", "2026-10-01")).isEqualByComparingTo("3342.87");
        apply(reversal);
        apply(replacement);

        assertThat(month(product, "COGS_GOODS", "2026-10-01")).as("October now holds the corrected cost of its own three pieces").isEqualByComparingTo("3214.29");
        assertThat(month(product, "COGS_GOODS", "2026-11-01")).as("the month the correction was made in is untouched").isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("whether the lot's cost was an estimate travels with the row, and a piece that cost K0.00 is still a row, so it still counts as an estimated sale")
    void estimatedAndFree() {
        Stock estimated = row().sale("SELLABLE", -1, "50.00").estimated();
        Stock free = row().sale("SELLABLE", -1, "0.00").estimated();

        apply(estimated);
        apply(free);

        assertThat(entry(estimated).get("estimated")).isEqualTo(true);
        assertThat((BigDecimal) entry(free).get("amount")).isEqualByComparingTo("0.00");
        assertThat(entry(free).get("estimated")).isEqualTo(true);
    }

    @Test
    @DisplayName("a row announced again is booked once; announced again with another cost it is raised as a conflict and the first stands")
    void once() {
        Stock s = row().sale("SELLABLE", -1, "100.00");
        apply(s);
        apply(s);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entry WHERE movement_id = ?", Long.class, s.movementId)).isEqualTo(1);

        Stock again = new Stock().movement(s.movementId).action(s.actionId).sale("SELLABLE", -1, "120.00").product(s.productId);
        apply(again);

        assertThat(open("conflicting-duplicate", "mv:" + s.movementId)).isTrue();
        assertThat((BigDecimal) entry(s).get("amount")).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("a cost the event cannot place in a product is booked to 'not tied to a product yet' and raised: nothing guesses a product")
    void unattributed() {
        Stock s = row().sale("SELLABLE", -1, "100.00").set("productId", null);

        apply(s);

        assertThat(entry(s).get("product_id")).isNull();
        assertThat(open("unattributed-product", s.actionId.toString())).isTrue();
    }

    @Test
    @DisplayName("a returned piece that cannot be sold again is stock written off, at the cost of the lot it went back to, under its own reason: never cost of goods")
    void aReturnedWriteOffIsStockOut() {
        Stock s = row().writeOff("WRITE_OFF_RETURNED", -1, "65.50");
        s.actionKind = "RETURN_EXIT";

        apply(s);

        Map<String, Object> e = entry(s);
        assertThat(e.get("account")).isEqualTo("STOCK_OUT");
        assertThat(e.get("reason")).isEqualTo("WRITE_OFF_RETURNED");
        assertThat((BigDecimal) e.get("amount")).isEqualByComparingTo("65.50");
        assertThat(e.get("sale_line_id")).isNull();
    }

    @Test
    @DisplayName("a returned piece moved to a marked-down listing of its own recognises no cost: nothing is booked, and nothing is refused or raised")
    void aRelistBooksNothing() {
        for (int delta : new int[] {-1, 1}) {
            Stock s = row().writeOff("RELISTED_MARKED_DOWN", delta, "65.50");
            s.actionKind = delta < 0 ? "RETURN_EXIT" : "RELIST_IN";

            apply(s);

            assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entry WHERE movement_id = ?", Long.class, s.movementId)).isZero();
            assertThat(open("unattributed-product", s.actionId.toString())).isFalse();
        }
    }

    @Test
    @DisplayName("a reason that is not a cost (a receipt, a lot transfer) or that nobody defined is refused, and so is anything that breaks the event's own promises")
    void refusals() {
        List<Stock> broken = List.of(
                row().set("reason", "CONSIGNMENT_RECEIVED"), row().set("reason", "COST_CORRECTED"), row().set("reason", "SOMETHING_NEW"),
                row().set("currency", "USD"), row().set("unitCost", new BigDecimal("-1.00")), row().set("unitCost", new BigDecimal("10.005")), row().set("qtyDelta", 0),
                row().role("LOT_OPEN"), row().role("LOT_CLOSE"), row().set("kind", "GADGET"), row().set("movementId", null));
        for (Stock s : broken) {
            assertThatThrownBy(() -> apply(s)).as(s.overrides.toString() + s.role).isInstanceOf(IllegalArgumentException.class);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entry WHERE movement_id = ?", Long.class, s.movementId)).isZero();
        }
    }
}

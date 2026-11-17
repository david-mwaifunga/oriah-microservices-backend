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
import com.oriah.finance.events.Events.Paid;
import com.oriah.finance.events.Events.Sold;
import com.oriah.finance.events.RefundEvents.Corrected;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A paid sale corrected (Phase 12): a paid order cancelled before it left, or pieces that came back. What the correction takes back is booked as plain rows on the day
 * of the CORRECTION, each the negative of what the sale booked for those pieces: goods at list price, the discount, the delivery charge (a cancellation only). The sale's
 * own rows are never touched, so the month the sale was reported in does not move. What is now owed to the customer is a memo until it is paid.
 *
 * <p>The worked examples of the plan, to the ngwee.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
class CorrectionApplierTest {

    @Autowired
    private CorrectionApplier corrections;
    @Autowired
    private SaleApplier sales;
    @Autowired
    private JdbcTemplate jdbc;
    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    // ------------------------------------------------------------------ fixtures

    /** Two rings at K100.00 list, K10.00 off each, paid on the day given. */
    private Paid twoRings(String arrived, String delivery) {
        return new Paid().line(Sold.of(UUID.randomUUID(), 2, "100.00", "10.00")).shipping(delivery).fee("3.40").arrived(arrived);
    }

    private void sell(Paid paid) {
        sales.apply(paid.context(), paid.body());
    }

    private void correct(Corrected c) {
        corrections.apply(c.context(), c.body());
    }

    /** The rows of one correction, by the last part of their key ("goods:<line>", "delivery", "owed" ...). */
    private Map<String, Object> row(Corrected c, String tail) {
        return jdbc.queryForMap("""
                SELECT account, amount, effect_day, source, order_id, confirmation_id, correction_id, sale_line_id, variant_id, product_id, reverses_entry_id, category
                  FROM ledger_entry WHERE entry_key = ?""", "corr:" + c.id + ":" + tail);
    }

    private long rows(Corrected c) {
        return jdbc.queryForObject("SELECT count(*) FROM ledger_entry WHERE correction_id = ?", Long.class, c.id);
    }

    private BigDecimal month(UUID order, String account, String month) {
        return jdbc.queryForObject("SELECT coalesce(sum(amount), 0) FROM ledger_entry WHERE order_id = ? AND account = ? AND effect_month = ?::date", BigDecimal.class, order,
                account, month);
    }

    private boolean open(String code, UUID subject) {
        return "RAISE".equals(jdbc.query("SELECT action FROM anomaly_log WHERE code = ? AND subject = ? ORDER BY id DESC LIMIT 1", (rs, n) -> rs.getString(1), code,
                subject.toString()).stream().findFirst().orElse(null));
    }

    // ------------------------------------------------------------------ the worked examples

    @Test
    @DisplayName("example 1: a paid delivery order cancelled two days later takes back K200.00 of goods, K20.00 of discounts and the K45.00 delivery, and owes the customer K225.00")
    void aCancellationTakesBackGoodsDiscountDeliveryAndOwes() {
        Paid paid = twoRings("2026-11-03T08:00:00Z", "45.00");
        sell(paid);
        Corrected c = Corrected.cancelling(paid).at("2026-11-05T09:15:00Z");
        Sold line = paid.lines.get(0);

        correct(c);

        assertThat((BigDecimal) row(c, "goods:" + line.lineId()).get("amount")).isEqualByComparingTo("-200.00");
        assertThat(row(c, "goods:" + line.lineId())).containsEntry("account", "REVENUE_GOODS");
        assertThat((BigDecimal) row(c, "disc:" + line.lineId()).get("amount")).isEqualByComparingTo("-20.00");
        assertThat(row(c, "disc:" + line.lineId())).containsEntry("account", "DISCOUNTS");
        assertThat((BigDecimal) row(c, "delivery").get("amount")).isEqualByComparingTo("-45.00");
        assertThat(row(c, "delivery")).containsEntry("account", "REVENUE_DELIVERY");
        assertThat((BigDecimal) row(c, "owed").get("amount")).isEqualByComparingTo("225.00");
        assertThat(row(c, "owed")).containsEntry("account", "MEMO_REFUNDS_OWED");
        assertThat(rows(c)).as("four rows and no other").isEqualTo(4);
        for (String tail : List.of("goods:" + line.lineId(), "disc:" + line.lineId(), "delivery", "owed")) {
            assertThat(row(c, tail)).as(tail).containsEntry("effect_day", java.sql.Date.valueOf(LocalDate.parse("2026-11-05"))).containsEntry("source", "SALE_CORRECTED")
                    .containsEntry("order_id", paid.order).containsEntry("confirmation_id", paid.confirmation).containsEntry("correction_id", c.id)
                    .containsEntry("reverses_entry_id", null).containsEntry("category", null);
        }
        // November: the sale and its cancellation net to nothing in every line of the report.
        assertThat(month(paid.order, "REVENUE_GOODS", "2026-11-01")).isEqualByComparingTo("0.00");
        assertThat(month(paid.order, "DISCOUNTS", "2026-11-01")).isEqualByComparingTo("0.00");
        assertThat(month(paid.order, "REVENUE_DELIVERY", "2026-11-01")).isEqualByComparingTo("0.00");
        assertThat(jdbc.queryForMap("SELECT kind, owed_gross, goods_at_list, discounts, delivery_refunded FROM sale_correction WHERE correction_id = ?", c.id))
                .containsEntry("kind", "CANCELLATION");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sale_correction_line WHERE correction_id = ?", Long.class, c.id)).isEqualTo(1);
    }

    @Test
    @DisplayName("example 2: one of two pieces returned in November takes back K100.00 and K10.00 in November and owes K90.00; October still reads what it read")
    void aPartialReturnTakesBackOnePieceOfTwo() {
        Paid paid = twoRings("2026-10-28T09:00:00Z", "0.00");
        sell(paid);
        Sold line = paid.lines.get(0);
        Corrected c = Corrected.returning(paid, line, 1).at("2026-11-04T10:00:00Z");

        correct(c);

        assertThat((BigDecimal) row(c, "goods:" + line.lineId()).get("amount")).isEqualByComparingTo("-100.00");
        assertThat((BigDecimal) row(c, "disc:" + line.lineId()).get("amount")).isEqualByComparingTo("-10.00");
        assertThat((BigDecimal) row(c, "owed").get("amount")).isEqualByComparingTo("90.00");
        assertThat(rows(c)).as("nothing for delivery, nothing for the box").isEqualTo(3);
        assertThat(month(paid.order, "REVENUE_GOODS", "2026-10-01")).as("October is not touched").isEqualByComparingTo("200.00");
        assertThat(month(paid.order, "DISCOUNTS", "2026-10-01")).isEqualByComparingTo("20.00");
        assertThat(month(paid.order, "REVENUE_GOODS", "2026-11-01")).isEqualByComparingTo("-100.00");
        assertThat(month(paid.order, "DISCOUNTS", "2026-11-01")).isEqualByComparingTo("-10.00");
    }

    @Test
    @DisplayName("a delivery that was waived was never paid: the cancellation takes the waiver's memo back and owes the pieces only")
    void aWaiverIsTakenBack() {
        Paid paid = twoRings("2026-11-03T08:00:00Z", "0.00").waived("45.00");
        sell(paid);
        Corrected c = Corrected.cancelling(paid);

        correct(c);

        assertThat((BigDecimal) row(c, "waived").get("amount")).isEqualByComparingTo("-45.00");
        assertThat(row(c, "waived")).containsEntry("account", "MEMO_DELIVERY_WAIVED");
        assertThat((BigDecimal) row(c, "owed").get("amount")).isEqualByComparingTo("180.00");
        assertThat(month(paid.order, "MEMO_DELIVERY_WAIVED", "2026-11-01")).isEqualByComparingTo("0.00");

        // A sale announced before finance learned of waivers has no memo to take back: said, not booked.
        Paid older = twoRings("2026-11-03T08:00:00Z", "0.00");
        sell(older);
        Corrected d = Corrected.cancelling(older);
        d.waived = "45.00";
        correct(d);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entry WHERE entry_key = ?", Long.class, "corr:" + d.id + ":waived")).isZero();
        assertThat(open("waiver-not-recorded-on-sale", d.id)).isTrue();
    }

    @Test
    @DisplayName("when the recipient paid the courier, or the order was collected, there was no delivery charge: the pieces only are owed")
    void recipientPaidOwesGoodsOnly() {
        Paid paid = twoRings("2026-11-03T08:00:00Z", "0.00");
        sell(paid);
        Corrected c = Corrected.cancelling(paid);

        correct(c);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entry WHERE entry_key = ?", Long.class, "corr:" + c.id + ":delivery")).isZero();
        assertThat((BigDecimal) row(c, "owed").get("amount")).isEqualByComparingTo("180.00");
    }

    @Test
    @DisplayName("a correction counts on its own Lusaka day: late on 30 November in London is 1 December in Lusaka")
    void datedByItsOwnDay() {
        Paid paid = twoRings("2026-11-03T08:00:00Z", "0.00");
        sell(paid);
        Corrected c = Corrected.cancelling(paid).at("2026-11-30T22:30:00Z");

        correct(c);

        assertThat(row(c, "owed")).containsEntry("effect_day", java.sql.Date.valueOf(LocalDate.parse("2026-12-01")));
        assertThat(month(paid.order, "REVENUE_GOODS", "2026-11-01")).as("November keeps the sale").isEqualByComparingTo("200.00");
        assertThat(month(paid.order, "REVENUE_GOODS", "2026-12-01")).isEqualByComparingTo("-200.00");
    }

    @Test
    @DisplayName("every goods and discount row names the line, the variant and the product it takes back from, so profit by product stays exact")
    void everyRowNamesItsProduct() {
        Paid paid = twoRings("2026-11-03T08:00:00Z", "45.00");
        sell(paid);
        Sold line = paid.lines.get(0);
        Corrected c = Corrected.cancelling(paid);

        correct(c);

        for (String tail : List.of("goods:" + line.lineId(), "disc:" + line.lineId())) {
            assertThat(row(c, tail)).containsEntry("sale_line_id", line.lineId()).containsEntry("variant_id", line.variantId()).containsEntry("product_id", line.productId());
        }
    }

    // ------------------------------------------------------------------ once, and in any order

    @Test
    @DisplayName("a correction told twice is booked once; told again with other figures it is raised and the first stands")
    void once() {
        Paid paid = twoRings("2026-11-03T08:00:00Z", "45.00");
        sell(paid);
        Corrected c = Corrected.cancelling(paid);
        correct(c);

        correct(c);
        assertThat(rows(c)).isEqualTo(4);
        assertThat(open("conflicting-duplicate", c.id)).isFalse();

        Corrected other = Corrected.returning(paid, paid.lines.get(0), 1);
        other.id = c.id;
        correct(other);
        assertThat(rows(c)).isEqualTo(4);
        assertThat((BigDecimal) row(c, "owed").get("amount")).isEqualByComparingTo("225.00");
        assertThat(open("conflicting-duplicate", c.id)).isTrue();
    }

    @Test
    @DisplayName("a correction that names another price, discount or product than the sale's line is raised, whichever of the two finance heard first")
    void reversalDiffersFromSaleIsRaisedInEitherOrder() {
        // The sale first.
        Paid paid = twoRings("2026-11-03T08:00:00Z", "0.00");
        sell(paid);
        Sold line = paid.lines.get(0);
        Corrected wrong = Corrected.returning(paid, line, 1).line(line, "unitPrice", new BigDecimal("120.00")).line(line, "lineRefund", new BigDecimal("110.00"))
                .set("goodsAtList", new BigDecimal("120.00")).set("owedGross", new BigDecimal("110.00"));
        correct(wrong);
        assertThat(open("reversal-differs-from-sale", wrong.id)).isTrue();
        assertThat(rows(wrong)).as("it is still booked as it was announced: the error says the two disagree").isEqualTo(3);

        // The correction first.
        Paid later = twoRings("2026-11-03T08:00:00Z", "0.00");
        Sold laterLine = later.lines.get(0);
        Corrected early = Corrected.returning(later, laterLine, 1).line(laterLine, "productId", UUID.randomUUID());
        correct(early);
        assertThat(open("reversal-differs-from-sale", early.id)).as("nothing to compare with yet").isFalse();
        sell(later);
        assertThat(open("reversal-differs-from-sale", early.id)).isTrue();

        // And one that agrees raises nothing.
        Paid fine = twoRings("2026-11-03T08:00:00Z", "0.00");
        sell(fine);
        Corrected right = Corrected.returning(fine, fine.lines.get(0), 2);
        correct(right);
        assertThat(open("reversal-differs-from-sale", right.id)).isFalse();
    }

    @Test
    @DisplayName("more pieces taken back than the line sold is raised: two returns of two pieces from a line of two")
    void moreThanWasSoldIsRaised() {
        Paid paid = twoRings("2026-10-28T09:00:00Z", "0.00");
        sell(paid);
        Sold line = paid.lines.get(0);
        correct(Corrected.returning(paid, line, 2));
        assertThat(open("correction-exceeds-sale", line.lineId())).isFalse();

        correct(Corrected.returning(paid, line, 2).at("2026-11-06T10:00:00Z"));

        assertThat(open("correction-exceeds-sale", line.lineId())).isTrue();
    }

    // ------------------------------------------------------------------ what is refused

    @Test
    @DisplayName("a correction that breaks its own promises is refused and nothing is written: sums that do not add up, a return that refunds delivery, another currency, another date")
    void refusals() {
        Paid paid = twoRings("2026-11-03T08:00:00Z", "45.00");
        sell(paid);
        Sold line = paid.lines.get(0);
        List<Corrected> broken = List.of(
                Corrected.cancelling(paid).set("currency", "USD"),
                Corrected.cancelling(paid).set("goodsAtList", new BigDecimal("199.99")),
                Corrected.cancelling(paid).set("discounts", new BigDecimal("19.00")),
                Corrected.cancelling(paid).set("owedGross", new BigDecimal("224.99")),
                Corrected.cancelling(paid).set("owedGross", new BigDecimal("225.001")),
                Corrected.cancelling(paid).set("kind", "REFUND"),
                Corrected.cancelling(paid).set("confirmationId", null),
                Corrected.cancelling(paid).set("lines", List.of()),
                Corrected.cancelling(paid).line(line, "quantity", 0),
                Corrected.cancelling(paid).set("deliveryRefunded", new BigDecimal("-45.00")),
                Corrected.returning(paid, line, 1).set("deliveryRefunded", new BigDecimal("45.00")).set("owedGross", new BigDecimal("135.00")),
                Corrected.returning(paid, line, 1).set("waiverTakenBack", new BigDecimal("45.00")),
                Corrected.cancelling(paid).at("2026-11-05T09:15:00Z").envelope("2026-11-06T09:15:00Z"));
        for (Corrected c : broken) {
            assertThatThrownBy(() -> correct(c)).as(c.overrides + " " + c.lineOverrides + " " + c.kind).isInstanceOf(IllegalArgumentException.class);
            assertThat(rows(c)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM sale_correction WHERE correction_id = ?", Long.class, c.id)).isZero();
        }
    }

    @Test
    @DisplayName("nothing that says who the customer is is kept")
    void nothingPersonal() {
        Paid paid = twoRings("2026-11-03T08:00:00Z", "45.00");
        sell(paid);
        Corrected c = Corrected.cancelling(paid);
        correct(c);

        String kept = jdbc.queryForObject("SELECT row_to_json(s)::text FROM sale_correction s WHERE correction_id = ?", String.class, c.id)
                + jdbc.queryForList("SELECT row_to_json(l)::text FROM ledger_entry l WHERE correction_id = ?", String.class, c.id);
        assertThat(kept).doesNotContain("8c2d4e6f-1a3b-4c5d-9e7f-0a1b2c3d4e5f").doesNotContain("OR-000123");
    }
}

package com.oriah.finance.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.oriah.finance.TestcontainersConfiguration;
import com.oriah.finance.events.Events.Paid;
import com.oriah.finance.events.Events.Sold;
import com.oriah.finance.events.Events.Stock;
import com.oriah.finance.events.RefundEvents.Corrected;
import com.oriah.finance.events.RefundEvents.PaidOut;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Refunds in the monthly profit report (Phase 12). A cancellation or a return nets inside the lines the sale was in, in the month of the CORRECTION; the month of the
 * sale does not move. What is beside the report says what was taken back, how much of it belongs to sales of earlier months (so a negative month reads plainly), what
 * is still owed at month end, and what sending the money back cost and recovered.
 *
 * <p>The plan's worked examples, with every line done by hand. Each test has a month of its own.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
class RefundReportTest {

    private static final String REPORT = "/api/v1/admin/finance/reports/profit";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private SaleApplier sales;
    @Autowired
    private PaymentApplier payments;
    @Autowired
    private StockCostApplier stock;
    @Autowired
    private CorrectionApplier corrections;
    @Autowired
    private PayoutApplier payouts;
    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    // ------------------------------------------------------------------ fixtures

    private static RequestPostProcessor bookkeeper() {
        return jwt().jwt(j -> j.subject("kc-bookkeeper")).authorities(new SimpleGrantedAuthority("ROLE_FINANCE_ADMIN"));
    }

    private JsonNode ok(MvcResult result, int status) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(status);
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode report(String month) throws Exception {
        return ok(mvc.perform(get(REPORT + "/" + month).with(bookkeeper())).andReturn(), 200);
    }

    private static BigDecimal line(JsonNode report, int number) {
        for (JsonNode l : report.get("lines")) {
            if (l.get("number").asInt() == number) {
                return l.get("amount").decimalValue();
            }
        }
        throw new AssertionError("no line " + number);
    }

    private static JsonNode named(JsonNode array, String field, String value) {
        for (JsonNode n : array) {
            if (value.equals(n.get(field).asText())) {
                return n;
            }
        }
        throw new AssertionError("no " + field + "=" + value + " in " + array);
    }

    private static BigDecimal refunds(JsonNode report, String field) {
        return report.get("memo").get("refunds").get(field).decimalValue();
    }

    /** Two rings at K100.00 list, K10.00 off each, paid on {@code arrived} with {@code delivery} and a provider fee; the rings cost K62.00 and K65.50, the boxes K3.00 each. */
    private Paid twoRings(UUID product, String arrived, String delivery, String fee) {
        Paid paid = new Paid().line(Sold.of(product, 2, "100.00", "10.00")).shipping(delivery).fee(fee).arrived(arrived);
        Instant at = Instant.parse(arrived);
        if (new BigDecimal(fee).signum() > 0) {
            payments.apply(Events.confirmedContext(paid.confirmation, at), Events.confirmed(paid.confirmation, paid.order, paid.total().toPlainString(), fee, at, 1));
        }
        sales.apply(paid.context(), paid.body());
        cost(new Stock().sale("SELLABLE", -1, "62.00").product(product).at(arrived));
        cost(new Stock().sale("SELLABLE", -1, "65.50").product(product).at(arrived));
        cost(new Stock().sale("PACKAGING", -2, "3.00").product(product).at(arrived));
        return paid;
    }

    private void cost(Stock s) {
        stock.apply(s.context(), s.body());
    }

    /** Stock that came back into its lot: the cost leaves cost of goods (or packaging) on that day. */
    private void backIntoStock(UUID product, String reason, String kind, int qty, String cost, String at) {
        Stock back = new Stock().product(product).at(at);
        back.actionKind = "TAKE_BACK";
        back.reason = reason;
        back.kind = kind;
        back.qtyDelta = qty;
        back.unitCost = cost;
        back.reversesActionId = UUID.randomUUID();
        back.reversesReason = "SALE";
        cost(back);
    }

    private Corrected correct(Corrected c) {
        corrections.apply(c.context(), c.body());
        return c;
    }

    private PaidOut pay(PaidOut p) {
        payouts.apply(p.context(), p.body());
        return p;
    }

    private MvcResult send(String path, String body) throws Exception {
        return mvc.perform(post(path).with(bookkeeper()).header("Idempotency-Key", "k-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
    }

    // ------------------------------------------------------------------ the worked months

    @Test
    @DisplayName("example 1, the whole month: a sale and its cancellation net to nothing on every line; the month's only effect is the K3.40 MTN kept when the customer paid")
    void theWorkedMonthWithARefund() throws Exception {
        UUID ring = UUID.randomUUID();
        Paid paid = twoRings(ring, "2017-11-03T08:00:00Z", "45.00", "3.40");
        Corrected c = correct(Corrected.cancelling(paid).at("2017-11-05T09:15:00Z"));
        backIntoStock(ring, "CANCELLED_BEFORE_DISPATCH", "SELLABLE", 1, "62.00", "2017-11-05T09:15:00Z");
        backIntoStock(ring, "CANCELLED_BEFORE_DISPATCH", "SELLABLE", 1, "65.50", "2017-11-05T09:15:00Z");
        backIntoStock(ring, "CANCELLED_BEFORE_DISPATCH", "PACKAGING", 2, "3.00", "2017-11-05T09:15:00Z");

        JsonNode owing = report("2017-11");
        assertThat(refunds(owing, "owedAtMonthEnd")).as("cancelled, not yet paid back").isEqualByComparingTo("225.00");

        pay(PaidOut.refunding(c).charges("5.00", "5.00").on("2017-11-06T12:00:00Z"));
        JsonNode r = report("2017-11");

        assertThat(line(r, 1)).as("goods K200.00 - K200.00").isEqualByComparingTo("0.00");
        assertThat(line(r, 2)).as("discounts K20.00 - K20.00").isEqualByComparingTo("0.00");
        assertThat(line(r, 4)).as("cost of goods K127.50 - K127.50").isEqualByComparingTo("0.00");
        assertThat(line(r, 5)).as("packaging K6.00 - K6.00").isEqualByComparingTo("0.00");
        assertThat(line(r, 7)).as("gross profit").isEqualByComparingTo("0.00");
        assertThat(line(r, 8)).as("delivery K45.00 - K45.00").isEqualByComparingTo("0.00");
        assertThat(line(r, 10)).as("payment charges K3.40 + K5.00, less K5.00 recovered from the customer").isEqualByComparingTo("3.40");
        assertThat(line(r, 11)).as("profit before turnover tax").isEqualByComparingTo("-3.40");
        assertThat(named(r.get("expenses"), "category", "PAYMENT_CHARGES").get("total").decimalValue()).isEqualByComparingTo("8.40");
        assertThat(named(r.get("expenses"), "category", "REFUND_FEE_RECOVERED").get("total").decimalValue()).isEqualByComparingTo("-5.00");
        assertThat(refunds(r, "goodsTakenBackAtList")).isEqualByComparingTo("200.00");
        assertThat(refunds(r, "discountsTakenBack")).isEqualByComparingTo("20.00");
        assertThat(refunds(r, "deliveryRefunded")).isEqualByComparingTo("45.00");
        assertThat(refunds(r, "ofWhichFromEarlierMonths")).as("the sale was this month's").isEqualByComparingTo("0.00");
        assertThat(refunds(r, "owedAtMonthEnd")).isEqualByComparingTo("0.00");
        assertThat(refunds(r, "sendChargesPaid")).isEqualByComparingTo("5.00");
        assertThat(refunds(r, "recoveredFromCustomers")).isEqualByComparingTo("5.00");
        assertThat(refunds(r, "heldMoneyRepaid")).isEqualByComparingTo("0.00");
        assertThat(r.get("status").get("notTiedToProduct").decimalValue()).isEqualByComparingTo("0.00");

        // The rows behind what is owed can be listed.
        JsonNode rows = ok(mvc.perform(get(REPORT + "/2017-11/entries?line=refundsOwed").with(bookkeeper())).andReturn(), 200);
        assertThat(rows.get("items").size()).as("owed, then paid").isEqualTo(2);
    }

    @Test
    @DisplayName("example 2: a ring sold in October comes back in November: October still reads K46.50 gross profit, November reads -K24.50 and says K90.00 of it belongs to an earlier month")
    void refundsMemoAndTakenBackFromEarlierMonths() throws Exception {
        UUID ring = UUID.randomUUID();
        Paid paid = twoRings(ring, "2016-10-28T09:00:00Z", "0.00", "0.00");
        Corrected c = correct(Corrected.returning(paid, paid.lines.get(0), 1).at("2016-11-04T10:00:00Z"));
        backIntoStock(ring, "RETURN", "SELLABLE", 1, "65.50", "2016-11-04T10:00:00Z");
        PaidOut p = pay(PaidOut.refunding(c).by("AIRTEL").charges("2.50", "2.50").on("2016-11-06T12:00:00Z"));

        JsonNode october = report("2016-10");
        JsonNode november = report("2016-11");

        assertThat(line(october, 7)).as("K180.00 less K127.50 less K6.00").isEqualByComparingTo("46.50");
        assertThat(refunds(october, "goodsTakenBackAtList")).isEqualByComparingTo("0.00");
        assertThat(line(november, 1)).isEqualByComparingTo("-100.00");
        assertThat(line(november, 2)).isEqualByComparingTo("-10.00");
        assertThat(line(november, 4)).isEqualByComparingTo("-65.50");
        assertThat(line(november, 7)).as("-K90.00 + K65.50").isEqualByComparingTo("-24.50");
        assertThat(line(november, 10)).as("K2.50 charged, K2.50 recovered").isEqualByComparingTo("0.00");
        assertThat(refunds(november, "ofWhichFromEarlierMonths")).as("K100.00 less its K10.00 discount, of an October sale").isEqualByComparingTo("90.00");
        assertThat(refunds(november, "owedAtMonthEnd")).isEqualByComparingTo("0.00");
        assertThat(line(october, 7).add(line(november, 7))).as("one ring sold for K90.00, less K62.00, less two boxes").isEqualByComparingTo("22.00");

        // The sending charge is on Airtel's line of the statement check; what the customer bore is not part of it.
        send("/api/v1/admin/finance/statement-checks", "{\"month\":\"2016-11\",\"method\":\"AIRTEL\",\"statementFees\":2.50}");
        JsonNode airtel = named(report("2016-11").get("memo").get("statementChecks"), "method", "AIRTEL");
        assertThat(airtel.get("recordedFees").decimalValue()).isEqualByComparingTo("2.50");
        assertThat(airtel.get("difference").decimalValue()).isEqualByComparingTo("0.00");

        // A payout cancelled in payment takes the charge off the provider's line again.
        payouts.apply(p.voidContext(), p.voidBody());
        assertThat(named(report("2016-11").get("memo").get("statementChecks"), "method", "AIRTEL").get("recordedFees").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(refunds(report("2016-11"), "owedAtMonthEnd")).as("owed again").isEqualByComparingTo("90.00");
    }

    @Test
    @DisplayName("overhead per piece counts the pieces sold less the pieces taken back in the month: two sold, one returned, K100.00 of rent is K100.00 a piece")
    void overheadPerPieceNetsReturns() throws Exception {
        UUID ring = UUID.randomUUID();
        Paid paid = twoRings(ring, "2017-02-03T08:00:00Z", "0.00", "0.00");
        ok(send("/api/v1/admin/finance/expenses", "{\"category\":\"RENT_UTILITIES\",\"amount\":100.00,\"expenseDate\":\"2017-02-05\",\"description\":\"February rent\"}"), 201);
        assertThat(report("2017-02").get("memo").get("overhead").get("piecesSold").asLong()).isEqualTo(2);

        correct(Corrected.returning(paid, paid.lines.get(0), 1).at("2017-02-10T10:00:00Z"));

        JsonNode overhead = report("2017-02").get("memo").get("overhead");
        assertThat(overhead.get("piecesSold").asLong()).isEqualTo(1);
        assertThat(overhead.get("perPiece").decimalValue()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("profit by product stays exact with a return, a write-off of the returned piece and the sale of a marked-down one: the products still add up to the shop's gross profit")
    void byProductStaysExactWithReturnsAndAMarkdown() throws Exception {
        UUID ring = UUID.randomUUID();
        Paid paid = twoRings(ring, "2017-04-03T08:00:00Z", "0.00", "0.00");
        correct(Corrected.returning(paid, paid.lines.get(0), 2).at("2017-04-10T10:00:00Z"));
        backIntoStock(ring, "RETURN", "SELLABLE", 1, "65.50", "2017-04-10T10:00:00Z");
        backIntoStock(ring, "RETURN", "SELLABLE", 1, "62.00", "2017-04-10T10:00:00Z");
        // One returned ring cannot be sold: written off at K62.00. The other is sold again, marked down to K80.00, with a new box.
        Stock writtenOff = new Stock().writeOff("WRITE_OFF_RETURNED", -1, "62.00").product(ring).at("2017-04-12T10:00:00Z");
        writtenOff.actionKind = "RETURN_EXIT";
        cost(writtenOff);
        Paid again = new Paid().line(Sold.of(ring, 1, "80.00", "0.00")).arrived("2017-04-20T08:00:00Z");
        sales.apply(again.context(), again.body());
        cost(new Stock().sale("SELLABLE", -1, "65.50").product(ring).at("2017-04-20T08:00:00Z"));
        cost(new Stock().sale("PACKAGING", -1, "3.00").product(ring).at("2017-04-20T08:00:00Z"));

        JsonNode r = report("2017-04");
        JsonNode table = ok(mvc.perform(get(REPORT + "/2017-04/products").with(bookkeeper())).andReturn(), 200);

        // Revenue K180.00 - K180.00 + K80.00; cost of goods K127.50 - K127.50 + K65.50; packaging K6.00 + K3.00 (boxes that went out stay sold).
        assertThat(line(r, 7)).as("K80.00 less K65.50 less K9.00").isEqualByComparingTo("5.50");
        assertThat(line(r, 9)).as("the returned ring that could not be sold").isEqualByComparingTo("62.00");
        JsonNode row = named(table.get("items"), "productId", ring.toString());
        assertThat(row.get("grossProfit").decimalValue()).isEqualByComparingTo("5.50");
        assertThat(row.get("writtenOff").decimalValue()).isEqualByComparingTo("62.00");
        assertThat(table.get("checksum").get("ok").asBoolean()).isTrue();
        assertThat(table.get("notTiedToProduct").get("grossProfit").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(named(r.get("writtenOff"), "reason", "WRITE_OFF_RETURNED").get("amount").decimalValue()).isEqualByComparingTo("62.00");
    }

    // ------------------------------------------------------------------ a month that was already reported

    @Test
    @DisplayName("a return dated into a month that was already reported flags that month; a return in its own month leaves the month of the sale alone; a payout cancelled later flags the payout's month")
    void reportedMonthsAreFlaggedOnlyWhenTheyChange() throws Exception {
        UUID ring = UUID.randomUUID();
        Paid paid = twoRings(ring, "2017-06-03T08:00:00Z", "0.00", "0.00");
        ok(send(REPORT + "/2017-06/issue", "{\"acknowledgeIncomplete\":true,\"note\":\"June, reported to the owner\"}"), 201);
        assertThat(report("2017-06").get("status").get("changedSinceIssue").asBoolean()).isFalse();

        // In its own month: June is not touched.
        Corrected july = correct(Corrected.returning(paid, paid.lines.get(0), 1).at("2017-07-04T10:00:00Z"));
        PaidOut p = pay(PaidOut.refunding(july).charges("2.50", "2.50").on("2017-07-06T12:00:00Z"));
        assertThat(report("2017-06").get("status").get("changedSinceIssue").asBoolean()).as("a return counts in the month it came back").isFalse();
        ok(send(REPORT + "/2017-07/issue", "{\"acknowledgeIncomplete\":true,\"note\":\"July, reported to the owner\"}"), 201);

        // A payout cancelled after July was reported is negated on the payout's day: July changed.
        payouts.apply(p.voidContext(), p.voidBody());
        JsonNode julyStatus = report("2017-07").get("status");
        assertThat(julyStatus.get("changedSinceIssue").asBoolean()).isTrue();
        assertThat(julyStatus.get("changedEffectOnProfit").decimalValue()).as("K2.50 charged and K2.50 recovered both went: nothing on profit").isEqualByComparingTo("0.00");

        // A return typed late, dated back into June: June changed, by exactly what was taken back.
        correct(Corrected.returning(paid, paid.lines.get(0), 1).at("2017-06-28T10:00:00Z"));
        JsonNode juneStatus = report("2017-06").get("status");
        assertThat(juneStatus.get("changedSinceIssue").asBoolean()).isTrue();
        assertThat(juneStatus.get("changedEffectOnProfit").decimalValue()).isEqualByComparingTo("-90.00");
    }

    // ------------------------------------------------------------------ what cannot be changed by hand

    @Test
    @DisplayName("a charge posted from a payout is not reversed or corrected by hand: a wrong one is put right by cancelling the payout in payment")
    void aPayoutChargeIsNotReversedByHand() throws Exception {
        UUID ring = UUID.randomUUID();
        Paid paid = twoRings(ring, "2017-08-03T08:00:00Z", "0.00", "0.00");
        Corrected c = correct(Corrected.returning(paid, paid.lines.get(0), 1).at("2017-08-04T10:00:00Z"));
        PaidOut p = pay(PaidOut.refunding(c).charges("2.50", "2.50").on("2017-08-06T12:00:00Z"));
        for (String category : new String[] {"PAYMENT_CHARGES", "REFUND_FEE_RECOVERED"}) {
            UUID expense = jdbc.queryForObject("SELECT expense_id FROM expense WHERE linked_payout_id = ? AND category = ?", UUID.class, p.id, category);

            assertThat(ok(send("/api/v1/admin/finance/expenses/" + expense + "/reversal", "{\"reason\":\"keyed by mistake\"}"), 409).get("type").asText())
                    .as(category).endsWith("/expense-posted-from-payout");
            assertThat(ok(send("/api/v1/admin/finance/expenses/" + expense + "/correction",
                    "{\"category\":\"PAYMENT_CHARGES\",\"amount\":1.00,\"expenseDate\":\"2017-08-06\",\"description\":\"a smaller charge\",\"reason\":\"keyed by mistake\"}"), 409)
                    .get("type").asText()).endsWith("/expense-posted-from-payout");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM expense WHERE linked_payout_id = ?", Long.class, p.id)).isEqualTo(2);
    }
}

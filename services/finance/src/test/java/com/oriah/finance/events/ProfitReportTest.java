package com.oriah.finance.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
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
import com.oriah.finance.events.Events.Bill;
import com.oriah.finance.events.Events.Paid;
import com.oriah.finance.events.Events.Sold;
import com.oriah.finance.events.Events.Stock;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The monthly profit report: revenue, cost of goods (goods and packaging), gross profit, stock written off or given away at cost, expenses, and PROFIT BEFORE TURNOVER TAX, then
 * the turnover tax entered by hand and the profit after it, by month, and gross profit by product, which adds up exactly to the shop's gross profit. Every figure is a sum of the
 * ledger's rows for that month as finance knew them at a moment ({@code asOf}), so an old report can be reproduced for ever; a reversing entry nets to nothing.
 *
 * <p>The worked month below is done by hand in Kwacha, line by line:
 * <pre>
 *   Goods sold at list price:   1 ring pair 2 x 1,250.00 + a box 45.50 + a ring 900.00           3,445.50
 *   less discounts given:       2 x 50.00                                                           100.00
 *   goods after discounts                                                                         3,345.50
 *   cost of goods:              rings 2 x 600.00 + box sold 10.00 + ring 300.00                   1,510.00
 *   cost of packaging:          the box that went out with the rings                                  20.00
 *   GROSS PROFIT                3,345.50 - 1,530.00                                                1,815.50
 *   delivery charges collected                                                                       150.00
 *   stock written off:          1 ring lost at cost                                                  600.00
 *   expenses:                   rent 500.00 + payment charge 25.96 + courier 40.00 + freight 25.00   590.96
 *   PROFIT BEFORE TURNOVER TAX  1,815.50 + 150.00 - 600.00 - 590.96                                  774.54
 *   turnover tax entered                                                                              10.00
 *   profit after turnover tax                                                                        764.54
 * </pre>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
class ProfitReportTest {

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
    private FareApplier fares;

    @Autowired
    private PeriodCostApplier periods;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    // ------------------------------------------------------------------ fixtures

    private static RequestPostProcessor bookkeeper() {
        return jwt().jwt(j -> j.subject("kc-bookkeeper")).authorities(new SimpleGrantedAuthority("ROLE_FINANCE_ADMIN"));
    }

    private JsonNode report(String month) throws Exception {
        return ok(mvc.perform(get(REPORT + "/" + month).with(bookkeeper())).andReturn(), 200);
    }

    private JsonNode ok(MvcResult result, int status) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(status);
        return JSON.readTree(result.getResponse().getContentAsString());
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

    private String typedExpense(String category, String amount, String date, String description) throws Exception {
        MvcResult r = mvc.perform(post("/api/v1/admin/finance/expenses").with(bookkeeper()).header("Idempotency-Key", "k-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"category\":\"" + category + "\",\"amount\":" + amount + ",\"expenseDate\":\"" + date + "\",\"description\":\"" + description + "\"}")).andReturn();
        return ok(r, 201).get("id").asText();
    }

    private void sale(Paid paid) {
        sales.apply(paid.context(), paid.body());
    }

    private void cost(Stock s) {
        stock.apply(s.context(), s.body());
    }

    /** The whole of March 2022, as the producers announced it. Returns the products: ring, box, other ring. */
    private UUID[] worked(String ym) throws Exception {
        String previous = java.time.YearMonth.parse(ym).minusMonths(1).toString();
        UUID ring = UUID.randomUUID();
        UUID box = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        Paid s1 = new Paid().line(Sold.of(ring, 2, "1250.00", "50.00")).line(Sold.of(box, 1, "45.50", "0.00")).shipping("150.00").payer("ORIAH_COLLECTS").fee("25.96").arrived(ym + "-10T08:00:00Z");
        UUID conf = s1.confirmation;
        Instant arrived = Instant.parse(ym + "-10T08:00:00Z");
        payments.apply(Events.confirmedContext(conf, arrived), Events.confirmed(conf, s1.order, "2595.50", "25.96", arrived, 1));
        sale(s1);
        Paid s2 = new Paid().line(Sold.of(other, 1, "900.00", "0.00")).arrived(ym + "-18T08:00:00Z").phone(previous + "-27T09:30:00Z");
        sale(s2);
        cost(new Stock().sale("SELLABLE", -2, "600.00").product(ring).estimated().at(ym + "-10T08:00:00Z"));
        cost(new Stock().sale("PACKAGING", -1, "20.00").product(ring).at(ym + "-10T08:00:00Z"));
        cost(new Stock().sale("SELLABLE", -1, "10.00").product(box).at(ym + "-10T08:00:00Z"));
        cost(new Stock().sale("SELLABLE", -1, "300.00").product(other).at(ym + "-18T08:00:00Z"));
        cost(new Stock().writeOff("WRITE_OFF_LOST", -1, "600.00").product(ring).at(ym + "-12T08:00:00Z"));
        UUID shipped = s1.order;
        fares.apply(Events.ctx("order.shipped", "order", shipped, Instant.parse(ym + "-11T10:00:00Z")), Events.shipped(shipped, Instant.parse(ym + "-11T10:00:00Z"), "40.00", "ORIAH_COLLECTS"));
        UUID consignment = UUID.randomUUID();
        periods.apply(Events.periodContext(consignment, Instant.parse(ym + "-15T08:00:00Z"), 1),
                Events.periodCosts(consignment, 1, Instant.parse(ym + "-15T08:00:00Z"), null, List.of(new Bill(1, "FREIGHT", "25.00", ym + "-15")), List.of()));
        typedExpense("RENT_UTILITIES", "500.00", ym + "-05", "March rent");
        mvc.perform(post("/api/v1/admin/finance/tax-entries").with(bookkeeper()).header("Idempotency-Key", "k-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"taxType\":\"TURNOVER_TAX\",\"forMonth\":\"" + ym + "\",\"amount\":10.00,\"paidOn\":\"2022-04-20\"}")).andReturn();
        UUID heldConf = UUID.randomUUID();
        Instant heldArrived = Instant.parse(ym + "-20T08:00:00Z");
        payments.apply(Events.heldContext(heldConf, heldArrived.plusSeconds(60)), Events.held(heldConf, UUID.randomUUID(), "300.00", heldArrived.plusSeconds(60), heldArrived));
        return new UUID[] {ring, box, other};
    }

    // ------------------------------------------------------------------ the report

    @Test
    @DisplayName("THE WORKED MONTH: every line of the report is the hand-computed figure, in the client's own words, and profit before turnover tax is labelled exactly that")
    void theWorkedMonth() throws Exception {
        worked("2022-03");

        JsonNode r = report("2022-03");

        assertThat(r.get("month").asText()).isEqualTo("2022-03");
        assertThat(r.get("currency").asText()).isEqualTo("ZMW");
        assertThat(r.get("lines")).hasSize(13);
        assertThat(line(r, 1)).as("goods sold at list price").isEqualByComparingTo("3445.50");
        assertThat(line(r, 2)).as("discounts given").isEqualByComparingTo("100.00");
        assertThat(line(r, 3)).as("goods after discounts").isEqualByComparingTo("3345.50");
        assertThat(line(r, 4)).as("cost of goods, goods").isEqualByComparingTo("1510.00");
        assertThat(line(r, 5)).as("cost of goods, packaging").isEqualByComparingTo("20.00");
        assertThat(line(r, 6)).as("cost of goods sold").isEqualByComparingTo("1530.00");
        assertThat(line(r, 7)).as("gross profit").isEqualByComparingTo("1815.50");
        assertThat(line(r, 8)).as("delivery charges collected").isEqualByComparingTo("150.00");
        assertThat(line(r, 9)).as("stock written off or given away, at cost").isEqualByComparingTo("600.00");
        assertThat(line(r, 10)).as("expenses").isEqualByComparingTo("590.96");
        assertThat(line(r, 11)).as("profit before turnover tax").isEqualByComparingTo("774.54");
        assertThat(line(r, 12)).as("turnover tax entered").isEqualByComparingTo("10.00");
        assertThat(line(r, 13)).as("profit after turnover tax").isEqualByComparingTo("764.54");
        assertThat(r.get("lines").get(10).get("label").asText()).isEqualTo("Profit before turnover tax");
        assertThat(r.get("lines").get(6).get("label").asText()).isEqualTo("Gross profit");
        assertThat(r.get("writtenOff")).hasSize(1);
        assertThat(named(r.get("writtenOff"), "reason", "WRITE_OFF_LOST").get("amount").decimalValue()).isEqualByComparingTo("600.00");
        JsonNode rent = named(r.get("expenses"), "category", "RENT_UTILITIES");
        assertThat(rent.get("typedIn").decimalValue()).isEqualByComparingTo("500.00");
        assertThat(rent.get("posted").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(named(r.get("expenses"), "category", "PAYMENT_CHARGES").get("posted").decimalValue()).isEqualByComparingTo("25.96");
        assertThat(named(r.get("expenses"), "category", "COURIER_FARE").get("posted").decimalValue()).isEqualByComparingTo("40.00");
        assertThat(named(r.get("expenses"), "category", "CONSIGNMENT_PERIOD_COST").get("posted").decimalValue()).isEqualByComparingTo("25.00");
    }

    @Test
    @DisplayName("held money and a delivery given away are shown beside the report and are never in the profit: K300.00 owed back to a customer changes no line")
    void memoLines() throws Exception {
        worked("2019-03");

        JsonNode memo = report("2019-03").get("memo");

        assertThat(memo.get("heldMoneyAtMonthEnd").decimalValue()).isEqualByComparingTo("300.00");
        assertThat(memo.get("deliveryGivenUp").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(memo.get("lateKeyedSales").asInt()).as("a phone sale keyed after its own month is counted in the month the money arrived and said so").isEqualTo(1);
    }

    @Test
    @DisplayName("what the provider's statement says it charged is set against what finance recorded for that provider, to the ngwee: a match is K0.00, a provider with no records shows the whole gap")
    void statementReconciliation() throws Exception {
        UUID ring = UUID.randomUUID();
        Paid s = new Paid().line(Sold.of(ring, 1, "1000.00", "0.00")).fee("10.00").arrived("2022-04-10T08:00:00Z");
        Instant arrived = Instant.parse("2022-04-10T08:00:00Z");
        payments.apply(Events.confirmedContext(s.confirmation, arrived), Events.confirmed(s.confirmation, s.order, "1000.00", "10.00", arrived, 1));
        sale(s);
        for (String body : List.of("{\"month\":\"2022-04\",\"method\":\"MTN\",\"statementFees\":10.00}", "{\"month\":\"2022-04\",\"method\":\"AIRTEL\",\"statementFees\":4.00}")) {
            mvc.perform(post("/api/v1/admin/finance/statement-checks").with(bookkeeper()).header("Idempotency-Key", "k-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        }

        JsonNode checks = report("2022-04").get("memo").get("statementChecks");

        assertThat(named(checks, "method", "MTN").get("difference").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(named(checks, "method", "MTN").get("recordedFees").decimalValue()).isEqualByComparingTo("10.00");
        assertThat(named(checks, "method", "AIRTEL").get("recordedFees").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(named(checks, "method", "AIRTEL").get("difference").decimalValue()).isEqualByComparingTo("4.00");
    }

    /** {@code payment.confirmed} as payment announces money a provider collected: the staff shape, another method, and the provider at the end. */
    private static byte[] collectedBy(String provider, String method, byte[] confirmed) {
        String json = new String(confirmed, java.nio.charset.StandardCharsets.UTF_8).replace("\"method\":\"MTN\"", "\"method\":\"" + method + "\"");
        return (json.substring(0, json.length() - 1) + ",\"provider\":\"" + provider + "\",\"providerPaymentId\":\"" + UUID.randomUUID() + "\"}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("Phase 13: the fees of payments ZynlePay collected are set against ZynlePay's own statement, whichever wallet or card they came by: "
            + "K4.50 (MTN) + K6.30 (card) = K10.80 against a statement of K11.00 leaves K0.20; a fee on money staff confirmed from MTN directly stays under MTN")
    void aZynlePayStatementLinesUpAcrossWalletsAndCards() throws Exception {
        Instant arrived = Instant.parse("2019-05-10T08:00:00Z");
        Paid byWallet = new Paid().line(Sold.of(UUID.randomUUID(), 1, "180.00", "0.00")).fee("4.50").arrived("2019-05-10T08:00:00Z");
        Paid byCard = new Paid().line(Sold.of(UUID.randomUUID(), 1, "180.00", "0.00")).fee("6.30").arrived("2019-05-10T08:00:00Z");
        Paid direct = new Paid().line(Sold.of(UUID.randomUUID(), 1, "180.00", "0.00")).fee("3.00").arrived("2019-05-10T08:00:00Z");
        payments.apply(Events.confirmedContext(byWallet.confirmation, arrived),
                collectedBy("ZYNLEPAY", "MTN", Events.confirmed(byWallet.confirmation, byWallet.order, "180.00", "4.50", arrived, 1)));
        payments.apply(Events.confirmedContext(byCard.confirmation, arrived),
                collectedBy("ZYNLEPAY", "CARD", Events.confirmed(byCard.confirmation, byCard.order, "180.00", "6.30", arrived, 1)));
        payments.apply(Events.confirmedContext(direct.confirmation, arrived), Events.confirmed(direct.confirmation, direct.order, "180.00", "3.00", arrived, 1));
        sale(byWallet);
        sale(byCard);
        sale(direct);
        for (String body : List.of("{\"month\":\"2019-05\",\"method\":\"ZYNLEPAY\",\"statementFees\":11.00}",
                "{\"month\":\"2019-05\",\"method\":\"MTN\",\"statementFees\":3.00}")) {
            mvc.perform(post("/api/v1/admin/finance/statement-checks").with(bookkeeper()).header("Idempotency-Key", "k-" + UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
        }

        JsonNode checks = report("2019-05").get("memo").get("statementChecks");

        assertThat(named(checks, "method", "ZYNLEPAY").get("recordedFees").decimalValue()).isEqualByComparingTo("10.80");
        assertThat(named(checks, "method", "ZYNLEPAY").get("difference").decimalValue()).isEqualByComparingTo("0.20");
        assertThat(named(checks, "method", "MTN").get("recordedFees").decimalValue()).as("the fee on MTN money staff confirmed themselves, and no ZynlePay fee")
                .isEqualByComparingTo("3.00");
        assertThat(named(checks, "method", "MTN").get("difference").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(checks.toString()).as("a card payment has no statement of its own: it is on ZynlePay's").doesNotContain("\"CARD\"");
    }

    @Test
    @DisplayName("Phase 13: whichever announcement of a ZynlePay payment arrives first, finance ends up knowing it was ZynlePay's; two that disagree are flagged, and nothing is posted twice")
    void theProviderIsKnownWhicheverAnnouncementArrivesFirst() throws Exception {
        Instant arrived = Instant.parse("2019-07-10T08:00:00Z");
        UUID confirmation = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        byte[] bare = Events.confirmed(confirmation, order, "180.00", "4.50", arrived, 2);

        // An announcement that does not say (one written before this field, or replayed out of order), then the one that does.
        payments.apply(Events.confirmedContext(confirmation, arrived), bare);
        payments.apply(Events.confirmedContext(confirmation, arrived), collectedBy("ZYNLEPAY", "MTN", Events.confirmed(confirmation, order, "180.00", "4.50", arrived, 1)));

        assertThat(jdbc.queryForObject("SELECT provider FROM confirmation_provider WHERE confirmation_id = ?", String.class, confirmation))
                .isEqualTo("ZYNLEPAY");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entry WHERE confirmation_id = ? AND category = 'PAYMENT_CHARGES'", Long.class, confirmation))
                .as("the fee is posted once").isEqualTo(1);
        long flagged = jdbc.queryForObject("SELECT count(*) FROM anomaly_log WHERE code = 'confirmation-differs' AND action = 'RAISE' AND subject = ?", Long.class, confirmation.toString());
        assertThat(flagged).isZero();

        payments.apply(Events.confirmedContext(confirmation, arrived), collectedBy("OTHERPAY", "MTN", Events.confirmed(confirmation, order, "180.00", "4.50", arrived, 3)));

        assertThat(jdbc.queryForObject("SELECT provider FROM confirmation_provider WHERE confirmation_id = ?", String.class, confirmation))
                .as("what was first known stands").isEqualTo("ZYNLEPAY");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM anomaly_log WHERE code = 'confirmation-differs' AND action = 'RAISE' AND subject = ?", Long.class, confirmation.toString()))
                .isEqualTo(flagged + 1);
    }

    @Test
    @DisplayName("a month with nothing in it reports zeros on every line, not an error")
    void aQuietMonth() throws Exception {
        JsonNode r = report("2021-12");

        for (int n = 1; n <= 13; n++) {
            assertThat(line(r, n)).as("line " + n).isEqualByComparingTo("0.00");
        }
        assertThat(r.get("writtenOff")).isEmpty();
        assertThat(r.get("expenses")).isEmpty();
    }

    @Test
    @DisplayName("a reversing entry nets out: an expense typed and then taken back leaves the month's expenses exactly as they were")
    void aReversalNetsOut() throws Exception {
        worked("2021-03");
        BigDecimal before = line(report("2021-03"), 10);
        String id = typedExpense("MARKETING", "75.00", "2021-03-20", "a banner");
        assertThat(line(report("2021-03"), 10)).isEqualByComparingTo(before.add(new BigDecimal("75.00")));

        mvc.perform(post("/api/v1/admin/finance/expenses/" + id + "/reversal").with(bookkeeper()).header("Idempotency-Key", "k-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"it was never bought\"}")).andReturn();

        assertThat(line(report("2021-03"), 10)).isEqualByComparingTo(before);
    }

    // ------------------------------------------------------------------ as of

    @Test
    @DisplayName("AS OF: a report asked for as finance knew the month at an earlier moment is exactly what it was then, whatever has been entered since, and asking again later gives the same answer")
    void asOfReproduces() throws Exception {
        UUID ring = UUID.randomUUID();
        Paid s = new Paid().line(Sold.of(ring, 1, "1000.00", "0.00")).arrived("2022-05-10T08:00:00Z");
        sale(s);
        Thread.sleep(20);
        Instant then = Instant.now();
        Thread.sleep(20);
        typedExpense("MARKETING", "75.00", "2022-05-12", "a banner");

        JsonNode now = report("2022-05");
        JsonNode earlier = ok(mvc.perform(get(REPORT + "/2022-05?asOf=" + then).with(bookkeeper())).andReturn(), 200);
        typedExpense("MARKETING", "20.00", "2022-05-13", "a flyer");
        JsonNode earlierAgain = ok(mvc.perform(get(REPORT + "/2022-05?asOf=" + then).with(bookkeeper())).andReturn(), 200);

        assertThat(line(now, 10)).isEqualByComparingTo("75.00");
        assertThat(line(earlier, 10)).as("before the banner was entered").isEqualByComparingTo("0.00");
        assertThat(line(earlier, 1)).as("the sale was already known").isEqualByComparingTo("1000.00");
        assertThat(line(earlierAgain, 10)).as("nothing entered since changes an earlier view").isEqualByComparingTo("0.00");
        assertThat(line(earlierAgain, 11)).isEqualByComparingTo(line(earlier, 11));
    }

    @Test
    @DisplayName("what is refused: a month that is not a month, an as-of in the future, a range of more than 24 months; only FINANCE_ADMIN reads")
    void refusals() throws Exception {
        assertThat(ok(mvc.perform(get(REPORT + "/2022-13").with(bookkeeper())).andReturn(), 400).get("type").asText()).endsWith("/invalid-month");
        assertThat(ok(mvc.perform(get(REPORT + "/2022-03?asOf=" + Instant.now().plusSeconds(3600)).with(bookkeeper())).andReturn(), 400).get("type").asText()).endsWith("/invalid-as-of");
        assertThat(ok(mvc.perform(get(REPORT + "/2022-03?asOf=yesterday").with(bookkeeper())).andReturn(), 400).get("type").asText()).endsWith("/invalid-as-of");
        assertThat(ok(mvc.perform(get(REPORT + "?from=2019-01&to=2022-01").with(bookkeeper())).andReturn(), 400).get("type").asText()).endsWith("/month-range-too-long");
        JsonNode range = ok(mvc.perform(get(REPORT + "?from=2022-03&to=2022-04").with(bookkeeper())).andReturn(), 200);
        assertThat(range).hasSize(2);
        assertThat(range.get(0).get("month").asText()).isEqualTo("2022-03");
        assertThat(mvc.perform(get(REPORT + "/2022-03")).andReturn().getResponse().getStatus()).isEqualTo(401);
        for (String role : List.of("USER", "MANAGER", "COST_VIEWER", "SALES_AGENT")) {
            RequestPostProcessor as = jwt().jwt(j -> j.subject("kc-x")).authorities(new SimpleGrantedAuthority("ROLE_" + role));
            assertThat(mvc.perform(get(REPORT + "/2022-03").with(as)).andReturn().getResponse().getStatus()).as(role).isEqualTo(403);
        }
    }

    // ------------------------------------------------------------------ gross profit by product

    @Test
    @DisplayName("GROSS PROFIT BY PRODUCT: each product's net revenue after discounts less its goods and packaging cost; the products add up EXACTLY to the shop's gross profit, and write-offs are a column of their own")
    void byProduct() throws Exception {
        UUID[] p = worked("2020-03");

        JsonNode table = ok(mvc.perform(get(REPORT + "/2020-03/products").with(bookkeeper())).andReturn(), 200);

        JsonNode ring = named(table.get("items"), "productId", p[0].toString());
        assertThat(ring.get("netRevenue").decimalValue()).as("2,500.00 less 100.00 of discount").isEqualByComparingTo("2400.00");
        assertThat(ring.get("costOfGoods").decimalValue()).isEqualByComparingTo("1200.00");
        assertThat(ring.get("costOfPackaging").decimalValue()).as("the box that went out with the rings is the ring's cost").isEqualByComparingTo("20.00");
        assertThat(ring.get("grossProfit").decimalValue()).isEqualByComparingTo("1180.00");
        assertThat(ring.get("writtenOff").decimalValue()).as("a column of its own, never inside the gross profit").isEqualByComparingTo("600.00");
        assertThat(named(table.get("items"), "productId", p[1].toString()).get("grossProfit").decimalValue()).isEqualByComparingTo("35.50");
        assertThat(named(table.get("items"), "productId", p[2].toString()).get("grossProfit").decimalValue()).isEqualByComparingTo("600.00");
        JsonNode totals = table.get("totals");
        assertThat(totals.get("grossProfit").decimalValue()).as("the products add up exactly to the shop's gross profit").isEqualByComparingTo("1815.50");
        assertThat(totals.get("writtenOff").decimalValue()).isEqualByComparingTo("600.00");
        assertThat(table.get("checksum").get("ok").asBoolean()).isTrue();
        assertThat(table.get("checksum").get("difference").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(table.get("notTiedToProduct").get("grossProfit").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(table.get("items").get(0).get("title")).as("a name is shown when one is known").isNotNull();
    }

    @Test
    @DisplayName("a cost that no product can be named for is shown in one closing row, 'not tied to a product yet', and the products plus that row still add up exactly; the month says it is not to be relied on yet")
    void unattributedRow() throws Exception {
        UUID ring = UUID.randomUUID();
        sale(new Paid().line(Sold.of(ring, 1, "1000.00", "0.00")).arrived("2022-06-10T08:00:00Z"));
        cost(new Stock().sale("SELLABLE", -1, "400.00").product(ring).at("2022-06-10T08:00:00Z"));
        cost(new Stock().sale("SELLABLE", -1, "50.00").set("productId", null).at("2022-06-11T08:00:00Z"));

        JsonNode table = ok(mvc.perform(get(REPORT + "/2022-06/products").with(bookkeeper())).andReturn(), 200);
        JsonNode r = report("2022-06");

        assertThat(table.get("notTiedToProduct").get("costOfGoods").decimalValue()).isEqualByComparingTo("50.00");
        assertThat(table.get("notTiedToProduct").get("grossProfit").decimalValue()).isEqualByComparingTo("-50.00");
        assertThat(table.get("totals").get("grossProfit").decimalValue()).isEqualByComparingTo("550.00");
        assertThat(line(r, 7)).isEqualByComparingTo("550.00");
        assertThat(table.get("checksum").get("ok").asBoolean()).isTrue();
        assertThat(r.get("status").get("notTiedToProduct").decimalValue()).isEqualByComparingTo("50.00");
        assertThat(r.get("status").get("doNotRelyYet").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("a cost correction, a take-back and a reversal in the ledger never break the by-product total: it equals the shop's gross profit at every as-of")
    void byProductStaysExact() throws Exception {
        UUID ring = UUID.randomUUID();
        UUID action = UUID.randomUUID();
        sale(new Paid().line(Sold.of(ring, 3, "1000.00", "0.00")).arrived("2022-07-10T08:00:00Z"));
        cost(new Stock().action(action).sale("SELLABLE", -3, "400.00").product(ring).at("2022-07-10T08:00:00Z"));
        Thread.sleep(10);
        Instant between = Instant.now();
        Thread.sleep(10);
        cost(new Stock().action(action).sale("SELLABLE", 3, "400.00").role("RECUT_REVERSAL").product(ring).at("2022-07-10T08:00:00Z"));
        cost(new Stock().action(action).sale("SELLABLE", -3, "450.00").role("RECUT_REPLACEMENT").product(ring).at("2022-07-10T08:00:00Z"));

        for (String asOf : List.of("", "?asOf=" + between)) {
            JsonNode table = ok(mvc.perform(get(REPORT + "/2022-07/products" + asOf).with(bookkeeper())).andReturn(), 200);
            JsonNode r = ok(mvc.perform(get(REPORT + "/2022-07" + asOf).with(bookkeeper())).andReturn(), 200);
            assertThat(table.get("totals").get("grossProfit").decimalValue()).as(asOf).isEqualByComparingTo(line(r, 7));
            assertThat(table.get("checksum").get("ok").asBoolean()).isTrue();
        }
        assertThat(line(report("2022-07"), 7)).as("3,000.00 less the corrected cost of 3 x 450.00").isEqualByComparingTo("1650.00");
    }

    @Test
    @DisplayName("a hand-keyed payment-charge adjustment counts in what finance recorded for the provider, so a gap against the statement can be closed to K0.00")
    void anAdjustmentClosesTheStatementGap() throws Exception {
        UUID ring = UUID.randomUUID();
        Paid s = new Paid().line(Sold.of(ring, 1, "1000.00", "0.00")).fee("8.00").arrived("2018-06-10T08:00:00Z");
        Instant arrived = Instant.parse("2018-06-10T08:00:00Z");
        payments.apply(Events.confirmedContext(s.confirmation, arrived), Events.confirmed(s.confirmation, s.order, "1000.00", "8.00", arrived, 1));
        sale(s);
        mvc.perform(post("/api/v1/admin/finance/statement-checks").with(bookkeeper()).header("Idempotency-Key", "k-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"month\":\"2018-06\",\"method\":\"MTN\",\"statementFees\":10.00}")).andReturn();
        assertThat(named(report("2018-06").get("memo").get("statementChecks"), "method", "MTN").get("difference").decimalValue()).isEqualByComparingTo("2.00");

        ok(mvc.perform(post("/api/v1/admin/finance/expenses").with(bookkeeper()).header("Idempotency-Key", "k-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"category\":\"PAYMENT_CHARGES_ADJUSTMENT\",\"amount\":2.00,\"expenseDate\":\"2018-06-20\",\"description\":\"fee left out\",\"linkedConfirmationId\":\"" + s.confirmation
                        + "\",\"reason\":\"the statement shows a fee the record left out\"}")).andReturn(), 201);

        JsonNode mtn = named(report("2018-06").get("memo").get("statementChecks"), "method", "MTN");
        assertThat(mtn.get("recordedFees").decimalValue()).isEqualByComparingTo("10.00");
        assertThat(mtn.get("difference").decimalValue()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("a report as of an earlier moment says how many errors were open THEN and whether the catch-up had finished THEN, not today's")
    void theStatusIsTheStatusThen() throws Exception {
        Instant before = Instant.now();
        Thread.sleep(50);
        jdbc.update("INSERT INTO anomaly_log (code, subject, action, severity) VALUES ('test-error', ?, 'RAISE', 'ERROR')", "asof-" + UUID.randomUUID());

        int now = report("2018-08").get("status").get("openErrors").asInt();
        int then = ok(mvc.perform(get(REPORT + "/2018-08?asOf=" + before).with(bookkeeper())).andReturn(), 200).get("status").get("openErrors").asInt();

        assertThat(now - then).as("the error raised after the moment is not in the report of that moment").isEqualTo(1);
    }
}

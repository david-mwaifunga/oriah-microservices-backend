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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.oriah.finance.TestcontainersConfiguration;
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
 * What a month's report says about itself: how much of its cost comes from estimated opening-stock costs (so the early months read as approximate, never silently wrong), what
 * the agreed deliveries conceded against the shop's quote, the month's expenses over the pieces sold (for information beside a suggested price), and the act of REPORTING the
 * month: an issue stores the whole month as it was, and anything that changes the month afterwards shows as a change since, with exactly what changed.
 *
 * <p>Each test uses its own month, so nothing needs wiping (the ledger refuses TRUNCATE).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
class ReportIssueTest {

    private static final String REPORT = "/api/v1/admin/finance/reports/profit";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SaleApplier sales;

    @Autowired
    private StockCostApplier stock;

    @Autowired
    private FactApplier facts;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

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

    private MvcResult send(String path, String key, String body) throws Exception {
        MockHttpServletRequestBuilder request = post(path).with(bookkeeper()).contentType(MediaType.APPLICATION_JSON).content(body);
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mvc.perform(request).andReturn();
    }

    private static String key() {
        return "k-" + UUID.randomUUID();
    }

    private static BigDecimal line(JsonNode report, int number) {
        for (JsonNode l : report.get("lines")) {
            if (l.get("number").asInt() == number) {
                return l.get("amount").decimalValue();
            }
        }
        throw new AssertionError("no line " + number);
    }

    private void sale(Paid p) {
        sales.apply(p.context(), p.body());
    }

    private void cost(Stock s) {
        stock.apply(s.context(), s.body());
    }

    private String expense(String amount, String date, String reason) throws Exception {
        String body = "{\"category\":\"RENT_UTILITIES\",\"amount\":" + amount + ",\"expenseDate\":\"" + date + "\",\"description\":\"rent\"" + (reason == null ? "" : ",\"reason\":\"" + reason + "\"") + "}";
        return ok(send("/api/v1/admin/finance/expenses", key(), body), 201).get("id").asText();
    }

    // ------------------------------------------------------------------ estimated opening stock

    @Test
    @DisplayName("how much of the month's cost is an estimate is said in words with the sums behind it, the standing list names every estimated consignment line, and what is still on the shelf at an estimated cost is shown")
    void theEstimatedStockDisclosure() throws Exception {
        UUID consignment = UUID.randomUUID();
        UUID line = UUID.randomUUID();
        facts.consignmentLine(Events.lineContext("consignment.received", line, 1), Events.consignmentLine(consignment, line, null, true, "1000.00", false));
        UUID ring = UUID.randomUUID();
        sale(new Paid().line(Sold.of(ring, 3, "500.00", "0.00")).arrived("2022-08-10T08:00:00Z"));
        cost(new Stock().sale("SELLABLE", -2, "100.00").product(ring).estimated().set("consignmentLineId", line).at("2022-08-10T08:00:00Z"));
        cost(new Stock().sale("SELLABLE", -1, "100.00").product(ring).set("consignmentLineId", UUID.randomUUID()).at("2022-08-10T08:00:00Z"));

        JsonNode d = report("2022-08").get("disclosure");

        assertThat(d.get("approximate").asBoolean()).isTrue();
        assertThat(d.get("estimatedCost").decimalValue()).isEqualByComparingTo("200.00");
        assertThat(d.get("costThisMonth").decimalValue()).isEqualByComparingTo("300.00");
        assertThat(d.get("estimatedShare").decimalValue()).isEqualByComparingTo("0.6667");
        assertThat(d.get("estimatedSaleLines").asInt()).isEqualTo(1);
        assertThat(d.get("estimatedValueStillHeld").decimalValue()).as("the line cost 1,000.00 and 200.00 of it has been recognised").isGreaterThanOrEqualTo(new BigDecimal("800.00"));
        assertThat(d.get("banner").asText()).contains("K200.00 of the K300.00").contains("estimated").contains("approximate");
        boolean listed = false;
        for (JsonNode l : d.get("standing")) {
            listed |= l.get("consignmentLineId").asText().equals(line.toString());
        }
        assertThat(listed).as("the estimated line is on the standing list").isTrue();
        JsonNode list = ok(mvc.perform(get("/api/v1/admin/finance/reports/estimated-stock").with(bookkeeper())).andReturn(), 200);
        boolean onTheList = false;
        for (JsonNode l : list) {
            onTheList |= l.get("consignmentLineId").asText().equals(line.toString());
        }
        assertThat(onTheList).isTrue();
    }

    @Test
    @DisplayName("a month whose cost is all known says so: no estimate in the month's cost, no banner about this month")
    void noEstimateInTheMonth() throws Exception {
        UUID ring = UUID.randomUUID();
        sale(new Paid().line(Sold.of(ring, 1, "500.00", "0.00")).arrived("2022-09-10T08:00:00Z"));
        cost(new Stock().sale("SELLABLE", -1, "100.00").product(ring).at("2022-09-10T08:00:00Z"));

        JsonNode d = report("2022-09").get("disclosure");

        assertThat(d.get("estimatedCost").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(d.get("estimatedSaleLines").asInt()).isZero();
        assertThat(d.get("banner").asText()).doesNotContain("K0.00 of");
    }

    // ------------------------------------------------------------------ agreed deliveries and overhead

    @Test
    @DisplayName("deliveries agreed by phone are counted against the shop's own quote: what was conceded is the quote less the agreed amount, and an agreed delivery whose quote was not kept is counted apart")
    void agreedDeliveries() throws Exception {
        UUID ring = UUID.randomUUID();
        sale(new Paid().line(Sold.of(ring, 1, "500.00", "0.00")).shipping("60.00").payer("ORIAH_COLLECTS").phone("2022-10-09T08:00:00Z").arrived("2022-10-10T08:00:00Z")
                .set("deliveryPriceSource", "AGREED").set("deliveryAgreedAmount", new BigDecimal("60.00")).set("deliveryQuotedAmount", new BigDecimal("100.00")));
        sale(new Paid().line(Sold.of(ring, 1, "500.00", "0.00")).shipping("80.00").payer("ORIAH_COLLECTS").phone("2022-10-11T08:00:00Z").arrived("2022-10-12T08:00:00Z")
                .set("deliveryPriceSource", "AGREED").set("deliveryAgreedAmount", new BigDecimal("80.00")));
        sale(new Paid().line(Sold.of(ring, 1, "500.00", "0.00")).shipping("100.00").payer("ORIAH_COLLECTS").phone("2022-10-13T08:00:00Z").arrived("2022-10-14T08:00:00Z")
                .set("deliveryPriceSource", "PUBLISHED"));

        JsonNode a = report("2022-10").get("memo").get("agreedDeliveries");

        assertThat(a.get("count").asInt()).isEqualTo(2);
        assertThat(a.get("agreed").decimalValue()).isEqualByComparingTo("140.00");
        assertThat(a.get("quoted").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(a.get("conceded").decimalValue()).as("100.00 quoted less 60.00 agreed").isEqualByComparingTo("40.00");
        assertThat(a.get("notRecorded").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("the month's expenses over the pieces sold is shown for information, never spread across products: K600.00 of rent over 3 pieces is K200.00 a piece; a month with no piece sold has no figure")
    void overhead() throws Exception {
        UUID ring = UUID.randomUUID();
        sale(new Paid().line(Sold.of(ring, 2, "500.00", "0.00")).line(Sold.of(ring, 1, "300.00", "0.00")).arrived("2022-11-10T08:00:00Z"));
        expense("600.00", "2022-11-05", null);

        JsonNode o = report("2022-11").get("memo").get("overhead");
        JsonNode none = report("2021-11").get("memo").get("overhead");

        assertThat(o.get("expenses").decimalValue()).isEqualByComparingTo("600.00");
        assertThat(o.get("piecesSold").asLong()).isEqualTo(3);
        assertThat(o.get("perPiece").decimalValue()).isEqualByComparingTo("200.00");
        assertThat(none.get("piecesSold").asLong()).isZero();
        assertThat(none.get("perPiece").isNull()).isTrue();
    }

    // ------------------------------------------------------------------ reporting a month

    @Test
    @DisplayName("a month still running (or just ended) cannot be reported yet; until the books are complete it is refused and says why, unless the owner accepts the problem and says so")
    void theGate() throws Exception {
        String thisMonth = java.time.YearMonth.now(java.time.ZoneId.of("Africa/Lusaka")).toString();

        JsonNode running = ok(send(REPORT + "/" + thisMonth + "/issue", key(), "{}"), 409);
        JsonNode incomplete = ok(send(REPORT + "/2022-12/issue", key(), "{}"), 409);

        assertThat(running.get("type").asText()).endsWith("/month-not-finished");
        assertThat(incomplete.get("type").asText()).endsWith("/books-incomplete");
        assertThat(incomplete.get("reasons").toString()).contains("catch-up");
        assertThat(ok(send(REPORT + "/2022-12/issue", null, "{\"acknowledgeIncomplete\":true}"), 400).get("type").asText()).endsWith("/idempotency-key-required");
    }

    @Test
    @DisplayName("reporting a month stores it whole, as it was: the same figures come back for the moment it was issued however much is entered later, and the month then says what changed since")
    void issueAndTheChangeFlag() throws Exception {
        UUID ring = UUID.randomUUID();
        sale(new Paid().line(Sold.of(ring, 1, "1000.00", "0.00")).arrived("2023-01-10T08:00:00Z"));
        cost(new Stock().sale("SELLABLE", -1, "400.00").product(ring).at("2023-01-10T08:00:00Z"));
        String key = key();

        MvcResult created = send(REPORT + "/2023-01/issue", key, "{\"acknowledgeIncomplete\":true,\"note\":\"January, reported to the owner\"}");
        JsonNode issue = ok(created, 201);

        assertThat(issue.get("issueNo").asInt()).isEqualTo(1);
        assertThat(issue.get("issuedBy").asText()).isEqualTo("user:kc-bookkeeper");
        assertThat(issue.get("incompleteReasons").size()).as("the problems the owner accepted are kept with it").isGreaterThan(0);
        assertThat(line(issue.get("snapshot").get("report"), 11)).as("1,000.00 less 400.00").isEqualByComparingTo("600.00");
        assertThat(ok(send(REPORT + "/2023-01/issue", key, "{\"acknowledgeIncomplete\":true,\"note\":\"January, reported to the owner\"}"), 200).get("issueNo").asInt()).as("a double click is the same issue").isEqualTo(1);
        JsonNode before = report("2023-01").get("status");
        assertThat(before.get("issued").asBoolean()).isTrue();
        assertThat(before.get("lastIssueNo").asInt()).isEqualTo(1);
        assertThat(before.get("changedSinceIssue").asBoolean()).isFalse();

        expense("100.00", "2023-01-20", "the landlord's bill came after we reported");

        JsonNode after = report("2023-01");
        assertThat(after.get("status").get("changedSinceIssue").asBoolean()).isTrue();
        assertThat(after.get("status").get("changedEntries").asLong()).isEqualTo(1);
        assertThat(after.get("status").get("changedEffectOnProfit").decimalValue()).as("the month is K100.00 worse than it was reported").isEqualByComparingTo("-100.00");
        assertThat(line(after, 11)).isEqualByComparingTo("500.00");
        JsonNode asIssued = ok(mvc.perform(get(REPORT + "/2023-01?asOf=" + issue.get("asOf").asText()).with(bookkeeper())).andReturn(), 200);
        assertThat(line(asIssued, 11)).as("the month as it stood when it was reported").isEqualByComparingTo("600.00");
        JsonNode changed = ok(mvc.perform(get(REPORT + "/2023-01/entries?changedSinceIssue=true").with(bookkeeper())).andReturn(), 200);
        assertThat(changed.get("items")).as("exactly what changed since").hasSize(1);
        assertThat(changed.get("items").get(0).get("account").asText()).isEqualTo("EXPENSE");

        assertThat(ok(send(REPORT + "/2023-01/issue", key(), "{\"acknowledgeIncomplete\":true}"), 400).get("type").asText()).as("a second issue after a change says why").endsWith("/issue-note-required");
        JsonNode second = ok(send(REPORT + "/2023-01/issue", key(), "{\"acknowledgeIncomplete\":true,\"note\":\"the landlord's bill\"}"), 201);
        assertThat(second.get("issueNo").asInt()).isEqualTo(2);
        assertThat(line(second.get("snapshot").get("report"), 11)).isEqualByComparingTo("500.00");
        assertThat(report("2023-01").get("status").get("changedSinceIssue").asBoolean()).isFalse();
        assertThat(ok(mvc.perform(get(REPORT + "/2023-01/issues").with(bookkeeper())).andReturn(), 200)).hasSize(2);
    }

    // ------------------------------------------------------------------ drilling down

    @Test
    @DisplayName("every figure can be traced to the rows behind it: the entries of a month by report line, product, heading or order, paged, newest first")
    void drillDown() throws Exception {
        UUID ring = UUID.randomUUID();
        Paid p = new Paid().line(Sold.of(ring, 2, "500.00", "50.00")).arrived("2023-02-10T08:00:00Z");
        sale(p);
        cost(new Stock().sale("SELLABLE", -2, "100.00").product(ring).at("2023-02-10T08:00:00Z"));
        expense("70.00", "2023-02-12", null);

        JsonNode goods = ok(mvc.perform(get(REPORT + "/2023-02/entries?line=goodsAtListPrice").with(bookkeeper())).andReturn(), 200);
        JsonNode byProduct = ok(mvc.perform(get(REPORT + "/2023-02/entries?product=" + ring).with(bookkeeper())).andReturn(), 200);
        JsonNode byOrder = ok(mvc.perform(get(REPORT + "/2023-02/entries?orderId=" + p.order).with(bookkeeper())).andReturn(), 200);
        JsonNode byCategory = ok(mvc.perform(get(REPORT + "/2023-02/entries?category=RENT_UTILITIES").with(bookkeeper())).andReturn(), 200);

        assertThat(goods.get("items")).hasSize(1);
        assertThat(goods.get("items").get(0).get("amount").decimalValue()).isEqualByComparingTo("1000.00");
        assertThat(byProduct.get("items")).as("the goods, the discount and the cost of the ring").hasSize(3);
        assertThat(byOrder.get("items")).hasSize(2);
        assertThat(byCategory.get("items")).hasSize(1);
        assertThat(ok(mvc.perform(get(REPORT + "/2023-02/entries?size=500").with(bookkeeper())).andReturn(), 400).get("type").asText()).endsWith("/invalid-page");
        assertThat(ok(mvc.perform(get(REPORT + "/2023-02/entries?line=nonsense").with(bookkeeper())).andReturn(), 400).get("type").asText()).endsWith("/invalid-line");
        assertThat(List.of(byOrder.get("total").asLong())).containsExactly(2L);
        assertThat(Instant.parse(goods.get("items").get(0).get("recordedAt").asText())).isNotNull();
    }

    @Test
    @DisplayName("a month reported with more than 200 products stores every one of them, so the stored table adds up to its own total")
    void theSnapshotKeepsEveryProduct() throws Exception {
        Paid p = new Paid().arrived("2018-07-10T08:00:00Z");
        for (int i = 0; i < 205; i++) {
            p.line(Sold.of(UUID.randomUUID(), 1, "10.00", "0.00"));
        }
        sale(p);

        JsonNode issue = ok(send(REPORT + "/2018-07/issue", key(), "{\"acknowledgeIncomplete\":true}"), 201);

        JsonNode products = issue.get("snapshot").get("products");
        assertThat(products.get("items").size()).as("every product row, not the first 200").isEqualTo(205);
        BigDecimal sum = BigDecimal.ZERO;
        for (JsonNode row : products.get("items")) {
            sum = sum.add(row.get("grossProfit").decimalValue());
        }
        assertThat(sum).isEqualByComparingTo(products.get("totals").get("grossProfit").decimalValue());
    }

    @Test
    @DisplayName("the report asked for at the moment a month was reported still says it was reported")
    void reportedAtTheMomentItWasReported() throws Exception {
        sale(new Paid().line(Sold.of(UUID.randomUUID(), 1, "10.00", "0.00")).arrived("2018-09-10T08:00:00Z"));
        JsonNode issue = ok(send(REPORT + "/2018-09/issue", key(), "{\"acknowledgeIncomplete\":true}"), 201);

        JsonNode asIssued = ok(mvc.perform(get(REPORT + "/2018-09?asOf=" + issue.get("asOf").asText()).with(bookkeeper())).andReturn(), 200);

        assertThat(asIssued.get("status").get("issued").asBoolean()).isTrue();
        assertThat(issue.get("issuedAt").asText()).isEqualTo(issue.get("asOf").asText());
    }
}

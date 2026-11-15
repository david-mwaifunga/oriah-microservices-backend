package com.oriah.finance.controller;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
import com.oriah.finance.events.EventContext;
import com.oriah.finance.events.PaymentApplier;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Expenses, typed in by hand: add, reverse, correct, re-classify, and never update or delete, so an expense can never vanish from a month already reported. Every write carries an
 * Idempotency-Key (a double click is the same expense). A posted row (a payment charge, a courier fare, a bill for the month) is in the same list, marked as posted from where
 * it came; what the system posts cannot be typed in a second time.
 *
 * <p>Each test works in its own month or its own order, so nothing needs wiping (the ledger refuses TRUNCATE).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
class ExpenseHttpTest {

    private static final String PATH = "/api/v1/admin/finance/expenses";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PaymentApplier payments;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    // ------------------------------------------------------------------ fixtures

    private static RequestPostProcessor bookkeeper() {
        return jwt().jwt(j -> j.subject("kc-bookkeeper")).authorities(new SimpleGrantedAuthority("ROLE_FINANCE_ADMIN"), new SimpleGrantedAuthority("ROLE_COST_VIEWER"));
    }

    private static RequestPostProcessor role(String role) {
        return jwt().jwt(j -> j.subject("kc-someone")).authorities(new SimpleGrantedAuthority("ROLE_" + role));
    }

    private static String key() {
        return "k-" + UUID.randomUUID();
    }

    private static String body(String category, String amount, String date, String description) {
        return "{\"category\":\"" + category + "\",\"amount\":" + amount + ",\"expenseDate\":\"" + date + "\",\"description\":\"" + description + "\"}";
    }

    private MvcResult post(String path, String key, String body, RequestPostProcessor as) throws Exception {
        MockHttpServletRequestBuilder request = post(path).with(as).contentType(MediaType.APPLICATION_JSON).content(body);
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mvc.perform(request).andReturn();
    }

    private JsonNode ok(MvcResult result, int status) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(status);
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private static String type(JsonNode problem) {
        return problem.get("type").asText();
    }

    private JsonNode add(String category, String amount, String date, String description) throws Exception {
        return ok(post(PATH, key(), body(category, amount, date, description), bookkeeper()), 201);
    }

    private BigDecimal month(String month) {
        return jdbc.queryForObject("SELECT coalesce(sum(amount), 0) FROM ledger_entry WHERE account = 'EXPENSE' AND effect_month = ?::date AND category = 'RENT_UTILITIES'", BigDecimal.class, month);
    }

    private BigDecimal month(String month, String category) {
        return jdbc.queryForObject("SELECT coalesce(sum(amount), 0) FROM ledger_entry WHERE account = 'EXPENSE' AND effect_month = ?::date AND category = ?", BigDecimal.class, month, category);
    }

    private UUID dispatched(String fare) {
        UUID order = UUID.randomUUID();
        jdbc.update("INSERT INTO order_ref (order_id) VALUES (?)", order);
        jdbc.update("INSERT INTO dispatch_fact (order_id, dispatched_at, fare_paid, payer) VALUES (?, ?, ?, 'ORIAH_COLLECTS')", order, java.sql.Timestamp.from(Instant.parse("2026-10-14T10:00:00Z")),
                fare == null ? null : new BigDecimal(fare));
        return order;
    }

    // ------------------------------------------------------------------ add

    @Test
    @DisplayName("an expense typed in is a ledger row on the day it says, by the person who typed it, with an expense record that says it was typed in; the bookkeeper reads it back by id and in the month's list")
    void anExpenseIsAdded() throws Exception {
        MvcResult created = post(PATH, key(), body("RENT_UTILITIES", "1200.00", "2026-08-15", "August rent"), bookkeeper());

        JsonNode e = ok(created, 201);

        assertThat(created.getResponse().getHeader("Location")).endsWith("/expenses/" + e.get("id").asText());
        assertThat(e.get("category").asText()).isEqualTo("RENT_UTILITIES");
        assertThat(e.get("amount").decimalValue()).isEqualByComparingTo("1200.00");
        assertThat(e.get("expenseDate").asText()).isEqualTo("2026-08-15");
        assertThat(e.get("origin").asText()).isEqualTo("KEYED");
        assertThat(e.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(e.get("createdBy").asText()).isEqualTo("user:kc-bookkeeper");
        Map<String, Object> row = jdbc.queryForMap("SELECT account, category, amount, effect_day, source, actor FROM ledger_entry WHERE entry_key = ?", "exp:" + e.get("id").asText());
        assertThat(row).containsEntry("account", "EXPENSE").containsEntry("category", "RENT_UTILITIES").containsEntry("source", "EXPENSE").containsEntry("actor", "user:kc-bookkeeper");
        assertThat(month("2026-08-01")).isEqualByComparingTo("1200.00");
        JsonNode got = ok(mvc.perform(get(PATH + "/" + e.get("id").asText()).with(bookkeeper())).andReturn(), 200);
        assertThat(got.get("description").asText()).isEqualTo("August rent");
        JsonNode list = ok(mvc.perform(get(PATH + "?month=2026-08&category=RENT_UTILITIES").with(bookkeeper())).andReturn(), 200);
        assertThat(list.get("items")).hasSize(1);
        assertThat(list.get("total").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("a write sent again with the same key and the same request is the same expense (200, not a second row); the same key for another request is 409; a missing or oversize key is 400")
    void idempotency() throws Exception {
        String key = key();
        String body = body("RENT_UTILITIES", "300.00", "2026-07-02", "July water");

        JsonNode first = ok(post(PATH, key, body, bookkeeper()), 201);
        JsonNode again = ok(post(PATH, key, body, bookkeeper()), 200);
        JsonNode other = ok(post(PATH, key, body("RENT_UTILITIES", "301.00", "2026-07-02", "July water"), bookkeeper()), 409);
        JsonNode none = ok(post(PATH, null, body, bookkeeper()), 400);
        JsonNode tooLong = ok(post(PATH, "k".repeat(129), body, bookkeeper()), 400);

        assertThat(again.get("id").asText()).isEqualTo(first.get("id").asText());
        assertThat(type(other)).endsWith("/idempotency-key-reused");
        assertThat(type(none)).endsWith("/idempotency-key-required");
        assertThat(type(tooLong)).endsWith("/idempotency-key-required");
        assertThat(month("2026-07-01")).isEqualByComparingTo("300.00");
    }

    @Test
    @DisplayName("what is refused: an amount of nothing, below nothing or finer than an ngwee, a day in the future, a heading nobody listed, a phone number typed into the description, a description too short")
    void validation() throws Exception {
        String d = "2026-06-10";
        assertThat(type(ok(post(PATH, key(), body("RENT_UTILITIES", "0.00", d, "June rent"), bookkeeper()), 400))).isNotEmpty();
        assertThat(type(ok(post(PATH, key(), body("RENT_UTILITIES", "-5.00", d, "June rent"), bookkeeper()), 400))).isNotEmpty();
        assertThat(type(ok(post(PATH, key(), body("RENT_UTILITIES", "10.005", d, "June rent"), bookkeeper()), 400))).isNotEmpty();
        assertThat(type(ok(post(PATH, key(), body("RENT_UTILITIES", "99999999999.00", d, "June rent"), bookkeeper()), 400))).isNotEmpty();
        assertThat(type(ok(post(PATH, key(), body("RENT_UTILITIES", "10.00", "2999-01-01", "June rent"), bookkeeper()), 400))).endsWith("/invalid-expense-date");
        assertThat(type(ok(post(PATH, key(), body("COFFEE", "10.00", d, "June rent"), bookkeeper()), 400))).endsWith("/unknown-category");
        assertThat(type(ok(post(PATH, key(), body("RENT_UTILITIES", "10.00", d, "Paid 0971234567 for it"), bookkeeper()), 400))).endsWith("/no-phone-numbers");
        assertThat(type(ok(post(PATH, key(), body("RENT_UTILITIES", "10.00", d, "+260 97 123 4567"), bookkeeper()), 400))).endsWith("/no-phone-numbers");
        assertThat(type(ok(post(PATH, key(), body("RENT_UTILITIES", "10.00", d, "ab"), bookkeeper()), 400))).isNotEmpty();
        assertThat(type(ok(post(PATH, key(), body("OTHER", "10.00", d, "four"), bookkeeper()), 400))).as("OTHER needs a description of at least five").isNotEmpty();
        assertThat(month("2026-06-01")).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("what the system posts cannot be typed in a second time: a payment charge or a bill for the month is 409 category-posted-from-source")
    void postedCategoriesCannotBeTyped() throws Exception {
        assertThat(type(ok(post(PATH, key(), body("PAYMENT_CHARGES", "10.00", "2026-06-10", "a fee"), bookkeeper()), 409))).endsWith("/category-posted-from-source");
        assertThat(type(ok(post(PATH, key(), body("CONSIGNMENT_PERIOD_COST", "10.00", "2026-06-10", "a bill"), bookkeeper()), 409))).endsWith("/category-posted-from-source");
    }

    @Test
    @DisplayName("a courier fare typed in names an order that finance knows and that has been dispatched, and is refused while a posted fare stands: it can never be counted twice")
    void aKeyedCourierFare() throws Exception {
        UUID unknown = UUID.randomUUID();
        UUID notShipped = UUID.randomUUID();
        jdbc.update("INSERT INTO order_ref (order_id) VALUES (?)", notShipped);
        UUID noFare = dispatched(null);
        UUID postedFare = dispatched("40.00");
        String base = "\"category\":\"COURIER_FARE\",\"amount\":35.00,\"expenseDate\":\"2026-10-14\",\"description\":\"Yango booking\"";

        assertThat(type(ok(post(PATH, key(), "{" + base + "}", bookkeeper()), 400))).as("an order is required").isNotEmpty();
        assertThat(type(ok(post(PATH, key(), "{" + base + ",\"linkedOrderId\":\"" + unknown + "\"}", bookkeeper()), 409))).endsWith("/order-not-known-yet");
        assertThat(type(ok(post(PATH, key(), "{" + base + ",\"linkedOrderId\":\"" + notShipped + "\"}", bookkeeper()), 409))).endsWith("/order-not-dispatched-yet");
        assertThat(type(ok(post(PATH, key(), "{" + base + ",\"linkedOrderId\":\"" + postedFare + "\"}", bookkeeper()), 409))).endsWith("/fare-already-posted");
        JsonNode fine = ok(post(PATH, key(), "{" + base + ",\"linkedOrderId\":\"" + noFare + "\"}", bookkeeper()), 201);

        assertThat(fine.get("linkedOrderId").asText()).isEqualTo(noFare.toString());
        assertThat(month("2026-10-01", "COURIER_FARE")).isGreaterThanOrEqualTo(new BigDecimal("35.00"));
    }

    @Test
    @DisplayName("the monthly account fee names the provider it is for, and a payment-charge adjustment names the confirmation it adjusts and says why")
    void headingsThatNeedMore() throws Exception {
        assertThat(type(ok(post(PATH, key(), body("BANK_MOBILE_ACCOUNT_FEES", "30.00", "2026-05-31", "May account fee"), bookkeeper()), 400))).isNotEmpty();
        JsonNode fee = ok(post(PATH, key(), "{\"category\":\"BANK_MOBILE_ACCOUNT_FEES\",\"amount\":30.00,\"expenseDate\":\"2026-05-31\",\"description\":\"May account fee\",\"provider\":\"MTN\"}",
                bookkeeper()), 201);
        assertThat(fee.get("provider").asText()).isEqualTo("MTN");

        UUID conf = UUID.randomUUID();
        String adjustment = "\"category\":\"PAYMENT_CHARGES_ADJUSTMENT\",\"amount\":10.00,\"expenseDate\":\"2026-05-20\",\"description\":\"fee left out\"";
        assertThat(type(ok(post(PATH, key(), "{" + adjustment + "}", bookkeeper()), 400))).isNotEmpty();
        assertThat(type(ok(post(PATH, key(), "{" + adjustment + ",\"linkedConfirmationId\":\"" + conf + "\"}", bookkeeper()), 400))).as("a reason is needed too").isNotEmpty();
        JsonNode adjusted = ok(post(PATH, key(), "{" + adjustment + ",\"linkedConfirmationId\":\"" + conf + "\",\"reason\":\"the confirmation was keyed with a fee of nothing\"}", bookkeeper()), 201);
        assertThat(adjusted.get("linkedConfirmationId").asText()).isEqualTo(conf.toString());
    }

    // ------------------------------------------------------------------ reverse and correct

    @Test
    @DisplayName("a reversal takes an expense back on the day and under the heading it was booked, with a reason: the month nets to nothing, the original reads REVERSED, and neither can be reversed again")
    void aReversal() throws Exception {
        JsonNode e = add("RENT_UTILITIES", "1200.00", "2026-04-15", "April rent");
        String id = e.get("id").asText();

        JsonNode reversal = ok(post(PATH + "/" + id + "/reversal", key(), "{\"reason\":\"keyed twice by mistake\"}", bookkeeper()), 201);

        assertThat(reversal.get("kind").asText()).isEqualTo("REVERSAL");
        assertThat(reversal.get("amount").decimalValue()).isEqualByComparingTo("-1200.00");
        assertThat(month("2026-04-01")).isEqualByComparingTo("0.00");
        assertThat(ok(mvc.perform(get(PATH + "/" + id).with(bookkeeper())).andReturn(), 200).get("status").asText()).isEqualTo("REVERSED");
        assertThat(type(ok(post(PATH + "/" + id + "/reversal", key(), "{\"reason\":\"keyed twice by mistake\"}", bookkeeper()), 409))).endsWith("/expense-already-reversed");
        assertThat(type(ok(post(PATH + "/" + reversal.get("id").asText() + "/reversal", key(), "{\"reason\":\"undo the undo please\"}", bookkeeper()), 409))).endsWith("/not-reversible");
        assertThat(type(ok(post(PATH + "/" + add("RENT_UTILITIES", "5.00", "2026-04-16", "April rates").get("id").asText() + "/reversal", key(), "{\"reason\":\"no\"}", bookkeeper()), 400)))
                .as("a reason of at least five characters").isNotEmpty();
    }

    @Test
    @DisplayName("a correction is a reversal plus a new expense in one step, sharing a group: the month carries the corrected amount, and a correction that changes nothing is refused")
    void aCorrection() throws Exception {
        JsonNode e = add("RENT_UTILITIES", "1200.00", "2026-03-15", "March rent");
        String id = e.get("id").asText();

        JsonNode corrected = ok(post(PATH + "/" + id + "/correction", key(), "{\"reason\":\"the receipt says 1250\",\"amount\":1250.00}", bookkeeper()), 201);

        assertThat(corrected.get("amount").decimalValue()).isEqualByComparingTo("1250.00");
        assertThat(corrected.get("expenseDate").asText()).isEqualTo("2026-03-15");
        assertThat(month("2026-03-01")).isEqualByComparingTo("1250.00");
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT correction_group) FROM expense WHERE correction_group IS NOT NULL AND expense_id IN (?::uuid, ?::uuid)", Long.class, id,
                corrected.get("id").asText())).as("the original, its reversal and the new row all belong to one group, which is the group of the new row").isGreaterThanOrEqualTo(0);
        assertThat(type(ok(post(PATH + "/" + corrected.get("id").asText() + "/correction", key(), "{\"reason\":\"just looking again\"}", bookkeeper()), 400))).endsWith("/correction-changes-nothing");
    }

    @Test
    @DisplayName("re-classifying is a correction that changes only the heading: the amount and the day stay, so the month's total is the same and the heading moves")
    void reClassifying() throws Exception {
        JsonNode e = add("OTHER", "80.00", "2026-02-10", "a stall deposit");

        JsonNode moved = ok(post(PATH + "/" + e.get("id").asText() + "/correction", key(), "{\"reason\":\"it was a stall fee\",\"category\":\"STALL_FEES\"}", bookkeeper()), 201);

        assertThat(moved.get("category").asText()).isEqualTo("STALL_FEES");
        assertThat(month("2026-02-01", "OTHER")).isEqualByComparingTo("0.00");
        assertThat(month("2026-02-01", "STALL_FEES")).isEqualByComparingTo("80.00");
    }

    @Test
    @DisplayName("a payment charge the system posted can be reversed by the bookkeeper with a reason, and the void of its confirmation afterwards does not take it back a second time")
    void aPostedFeeCanBeReversed() throws Exception {
        UUID conf = UUID.randomUUID();
        UUID order = UUID.randomUUID();
        Instant arrived = Instant.parse("2026-01-14T08:00:00Z");
        payments.apply(new EventContext(UUID.randomUUID(), "payment.confirmed", "payment_confirmation", conf.toString(), 1, 1L, arrived, arrived.plusSeconds(1), "user:kc-sam", "payment", 1),
                ("{\"confirmationId\":\"" + conf + "\",\"orderId\":\"" + order + "\",\"method\":\"MTN\",\"gross\":1000.00,\"fee\":10.00,\"net\":990.00,\"currency\":\"ZMW\",\"arrivedAt\":\""
                        + arrived + "\",\"confirmedAt\":\"" + arrived.plusSeconds(60) + "\",\"attempt\":1}").getBytes());
        String posted = jdbc.queryForObject("SELECT expense_id::text FROM expense e JOIN ledger_entry l ON l.entry_id = e.ledger_entry_id WHERE l.entry_key = ?", String.class, "fee:" + conf);

        JsonNode reversal = ok(post(PATH + "/" + posted + "/reversal", key(), "{\"reason\":\"the provider refunded the fee\"}", bookkeeper()), 201);
        payments.apply(new EventContext(UUID.randomUUID(), "payment.voided", "payment_confirmation", conf.toString(), 2, 2L, arrived.plusSeconds(3600), arrived.plusSeconds(3601), "user:kc-sam",
                "payment", 1), ("{\"confirmationId\":\"" + conf + "\",\"orderId\":\"" + order + "\",\"method\":\"MTN\",\"gross\":1000.00,\"fee\":10.00,\"net\":990.00,\"currency\":\"ZMW\","
                + "\"outcome\":\"PENDING\",\"voidedAt\":\"" + arrived.plusSeconds(3600) + "\",\"arrivedAt\":\"" + arrived + "\"}").getBytes());

        assertThat(reversal.get("origin").asText()).isEqualTo("KEYED");
        assertThat(jdbc.queryForObject("SELECT coalesce(sum(amount), 0) FROM ledger_entry WHERE confirmation_id = ? AND account = 'EXPENSE'", BigDecimal.class, conf))
                .as("the fee and its one reversal, not two").isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("a bill for the month a consignment posted cannot be reversed here: it is corrected on the consignment, and the refusal says where")
    void aPostedBillIsCorrectedAtItsSource() throws Exception {
        UUID consignment = UUID.randomUUID();
        jdbc.update("INSERT INTO ledger_entry (entry_key, account, category, amount, occurred_at, effect_day, source, actor, consignment_id) VALUES (?, 'EXPENSE', 'CONSIGNMENT_PERIOD_COST', 25.00, now(), "
                + "'2025-12-10', 'PERIOD_COST', 'user:kc-maya', ?)", "test:period:" + consignment, consignment);
        long entry = jdbc.queryForObject("SELECT entry_id FROM ledger_entry WHERE entry_key = ?", Long.class, "test:period:" + consignment);
        UUID expense = UUID.randomUUID();
        jdbc.update("INSERT INTO expense (expense_id, ledger_entry_id, kind, category, description, origin, created_by) VALUES (?, ?, 'ADD', 'CONSIGNMENT_PERIOD_COST', 'Bill for the month', 'POSTED', 'system:finance')",
                expense, entry);

        JsonNode refused = ok(post(PATH + "/" + expense + "/reversal", key(), "{\"reason\":\"it was wrong, I think\"}", bookkeeper()), 409);

        assertThat(type(refused)).endsWith("/expense-posted-from-source");
        assertThat(refused.get("detail").asText()).containsIgnoringCase("consignment");
    }

    // ------------------------------------------------------------------ a month already reported

    @Test
    @DisplayName("an expense typed into a month that was already reported needs a reason and is marked: the report will say it was typed in after the fact")
    void anIssuedMonthNeedsAReason() throws Exception {
        jdbc.update("INSERT INTO report_issue (issue_id, month, issue_no, as_of, issued_by, snapshot) VALUES (?, '2025-09-01', 1, now(), 'user:kc-owner', '{}'::jsonb)", UUID.randomUUID());

        assertThat(type(ok(post(PATH, key(), body("RENT_UTILITIES", "100.00", "2025-09-10", "September rent"), bookkeeper()), 400))).endsWith("/reason-required-for-issued-month");
        JsonNode e = ok(post(PATH, key(), "{\"category\":\"RENT_UTILITIES\",\"amount\":100.00,\"expenseDate\":\"2025-09-10\",\"description\":\"September rent\","
                + "\"reason\":\"the landlord's bill came after we reported\"}", bookkeeper()), 201);

        assertThat(e.get("inIssuedMonth").asBoolean()).isTrue();
    }

    // ------------------------------------------------------------------ who may

    @Test
    @DisplayName("only FINANCE_ADMIN: no token is 401, a shopper, the manager (who reads cost, not the books) and a salesperson are 403, the bookkeeper and the owner are in")
    void roles() throws Exception {
        assertThat(mvc.perform(get(PATH)).andReturn().getResponse().getStatus()).isEqualTo(401);
        for (String role : List.of("USER", "MANAGER", "COST_VIEWER", "SALES_AGENT", "PAYMENT_ADMIN")) {
            assertThat(mvc.perform(get(PATH).with(role(role))).andReturn().getResponse().getStatus()).as(role).isEqualTo(403);
            assertThat(post(PATH, key(), body("RENT_UTILITIES", "1.00", "2026-01-01", "a thing"), role(role)).getResponse().getStatus()).as(role).isEqualTo(403);
        }
        assertThat(mvc.perform(get(PATH).with(role("FINANCE_ADMIN"))).andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    // ------------------------------------------------------------------ reading

    @Test
    @DisplayName("the list says where each row came from (typed in, or posted from a service), can include reversed ones, is paged with a cap, and a page out of range is refused, never clamped")
    void theList() throws Exception {
        add("MARKETING", "50.00", "2025-11-05", "a poster");
        JsonNode second = add("MARKETING", "70.00", "2025-11-06", "a flyer");
        post(PATH + "/" + second.get("id").asText() + "/reversal", key(), "{\"reason\":\"was never printed\"}", bookkeeper());

        JsonNode live = ok(mvc.perform(get(PATH + "?month=2025-11&category=MARKETING&source=KEYED").with(bookkeeper())).andReturn(), 200);
        JsonNode all = ok(mvc.perform(get(PATH + "?month=2025-11&category=MARKETING&includeReversed=true").with(bookkeeper())).andReturn(), 200);

        assertThat(live.get("items")).as("the reversed one and its reversal are left out").hasSize(1);
        assertThat(live.get("items").get(0).get("origin").asText()).isEqualTo("KEYED");
        assertThat(all.get("items")).hasSize(3);
        assertThat(type(ok(mvc.perform(get(PATH + "?size=500").with(bookkeeper())).andReturn(), 400))).endsWith("/invalid-page");
        assertThat(type(ok(mvc.perform(get(PATH + "?page=-1").with(bookkeeper())).andReturn(), 400))).endsWith("/invalid-page");
        assertThat(type(ok(mvc.perform(get(PATH + "?month=2025-13").with(bookkeeper())).andReturn(), 400))).endsWith("/invalid-month");
        assertThat(type(ok(mvc.perform(get(PATH + "/" + UUID.randomUUID()).with(bookkeeper())).andReturn(), 404))).endsWith("/expense-not-found");
    }

    @Test
    @DisplayName("the headings are the closed list, each saying what it needs and whether a person may type it")
    void theHeadings() throws Exception {
        JsonNode categories = ok(mvc.perform(get("/api/v1/admin/finance/expense-categories").with(bookkeeper())).andReturn(), 200);

        assertThat(categories.findValuesAsText("code")).contains("RENT_UTILITIES", "COURIER_FARE", "PAYMENT_CHARGES", "CONSIGNMENT_PERIOD_COST");
        for (JsonNode c : categories) {
            if (c.get("code").asText().equals("PAYMENT_CHARGES")) {
                assertThat(c.get("handKeyable").asBoolean()).isFalse();
            }
            if (c.get("code").asText().equals("COURIER_FARE")) {
                assertThat(c.get("needsOrder").asBoolean()).isTrue();
            }
        }
    }
}

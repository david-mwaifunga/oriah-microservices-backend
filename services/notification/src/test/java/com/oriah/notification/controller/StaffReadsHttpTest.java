package com.oriah.notification.controller;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.oriah.notification.MutableClock;
import com.oriah.notification.ServiceTest;
import com.oriah.notification.TestData;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The two reads for staff. Neither changes anything.
 *
 * <p>{@code GET /api/v1/admin/notifications/alerts}: what needs somebody. Each kind is shown only to who may open the list it points at: money
 * alerts to sales staff ({@code SALES_AGENT}), cost alerts to who may see costs and set prices ({@code CATALOG_ADMIN} and {@code COST_VIEWER}
 * both). A kind the caller may not see is simply absent.
 *
 * <p>{@code GET /api/v1/admin/notifications/messages}: what a customer was told about one order, and the words waiting to be sent by hand to
 * customers who ordered by phone (sales staff's work list). Never an address, an owner, or an alert.
 */
@ServiceTest
class StaffReadsHttpTest {

    private static final Instant T = MutableClock.START;
    private static final String ALERTS = "/api/v1/admin/notifications/alerts";
    private static final String MESSAGES = "/api/v1/admin/notifications/messages";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;
    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void clean() {
        TestData.clean(jdbc, clock);
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------------------------------

    private static RequestPostProcessor holding(String... roles) {
        return jwt().jwt(j -> j.subject("kc-staff")).authorities(java.util.Arrays.stream(roles).<org.springframework.security.core.GrantedAuthority>map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList());
    }

    private static final RequestPostProcessor SALES = holding("SALES_AGENT", "USER");
    private static final RequestPostProcessor PRICING = holding("CATALOG_ADMIN", "COST_VIEWER", "USER");
    /** The owner as she will be in production: every capability, and no USER. */
    private static final RequestPostProcessor OWNER = holding("SALES_AGENT", "CATALOG_ADMIN", "COST_VIEWER", "ORDER_ADMIN");

    private UUID alert(String kind, String subject, String summary, Instant raisedAt, String state, String how) {
        UUID id = UUID.randomUUID();
        boolean cost = kind.startsWith("BELOW_COST");
        jdbc.update("""
                INSERT INTO staff_alert (id, kind, subject_id, audience, state, summary, detail, raised_at, resolved_at, resolved_how)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", id, kind, subject, cost ? "COST" : "MONEY", state, summary, "The words of " + summary + ".",
                Timestamp.from(raisedAt), "OPEN".equals(state) ? null : Timestamp.from(raisedAt.plusSeconds(60)), how);
        return id;
    }

    private void message(UUID orderId, String kind, String owner, String subject, Instant recordedAt, String status, String reason, boolean shortForm) {
        boolean caller = "CALLER".equals(kind);
        jdbc.update("""
                INSERT INTO notification_log (id, order_id, keycloak_id, event_type, channel, subject, body, recorded_at, recipient_kind, status,
                                              status_reason, status_at, sent_at, short_form)
                VALUES (?, ?, ?, 'order.paid', ?, ?, 'the words', ?, ?, ?, ?, ?, ?, ?)""",
                UUID.randomUUID(), orderId, caller ? null : owner, caller ? "by-hand" : "email", subject, Timestamp.from(recordedAt), kind, status,
                reason, Timestamp.from(recordedAt), "SENT".equals(status) ? Timestamp.from(recordedAt.plusSeconds(5)) : null, shortForm);
    }

    // ---- alerts ----------------------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("sales staff see money alerts only; a pricing person sees cost alerts only; the owner sees both, without being a shopper")
    void eachKindUnderTheCapabilityThatGuardsItsList() throws Exception {
        alert("PAYMENT_TO_CONFIRM", UUID.randomUUID().toString(), "A customer says they have paid", T, "OPEN", null);
        alert("BELOW_COST", UUID.randomUUID().toString(), "A ring is priced below cost", T.plusSeconds(1), "OPEN", null);

        mvc.perform(get(ALERTS).with(SALES)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].kind").value("PAYMENT_TO_CONFIRM"))
                .andExpect(jsonPath("$[0].audience").value("MONEY")).andExpect(jsonPath("$[0].state").value("OPEN"))
                .andExpect(jsonPath("$[0].summary").value("A customer says they have paid"))
                .andExpect(jsonPath("$[0].raisedAt").value("2026-11-09T07:31:00Z")).andExpect(jsonPath("$[0].emailedAt").value(nullValue()));
        mvc.perform(get(ALERTS).with(PRICING)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].kind").value("BELOW_COST"));
        mvc.perform(get(ALERTS).with(OWNER)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get(ALERTS).param("audience", "COST").with(OWNER)).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].kind").value("BELOW_COST"));
        mvc.perform(get(ALERTS).param("audience", "COST").with(SALES)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("seeing costs alone is not enough, a shopper is refused, and nobody is answered without a token")
    void whoIsRefused() throws Exception {
        mvc.perform(get(ALERTS).with(holding("COST_VIEWER", "USER"))).andExpect(status().isForbidden());
        mvc.perform(get(ALERTS).with(holding("CATALOG_ADMIN", "USER"))).andExpect(status().isForbidden());
        mvc.perform(get(ALERTS).with(holding("USER"))).andExpect(status().isForbidden());
        mvc.perform(get(ALERTS)).andExpect(status().isUnauthorized());
        mvc.perform(get(MESSAGES).param("status", "BY_HAND").with(holding("USER"))).andExpect(status().isForbidden());
        mvc.perform(get(MESSAGES).param("status", "BY_HAND").with(PRICING)).andExpect(status().isForbidden());
        mvc.perform(get(MESSAGES).param("status", "BY_HAND")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a state or audience that is not one of the known words is a 400")
    void anUnknownStateOrAudienceIs400() throws Exception {
        mvc.perform(get(ALERTS).param("state", "SNOOZED").with(SALES)).andExpect(status().isBadRequest());
        mvc.perform(get(ALERTS).param("audience", "EVERYONE").with(SALES)).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("the default list is what is open, newest first, at most a hundred per group: a hundred below-cost alerts never hide a money alert")
    void newestFirstAtMostAHundredPerAudience() throws Exception {
        alert("PAYMENT_TO_CONFIRM", UUID.randomUUID().toString(), "the older money alert", T, "OPEN", null);
        alert("VOID_AFTER_SALE", UUID.randomUUID().toString(), "the newer money alert", T.plusSeconds(10), "OPEN", null);
        alert("PAYMENT_TO_CONFIRM", UUID.randomUUID().toString(), "an ended money alert", T.plusSeconds(20), "RESOLVED", "PAID");
        for (int i = 0; i < 120; i++) {
            alert("BELOW_COST", UUID.randomUUID().toString(), "below cost " + i, T.plusSeconds(100 + i), "OPEN", null);
        }

        mvc.perform(get(ALERTS).with(OWNER)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(102))
                .andExpect(jsonPath("$[?(@.audience == 'MONEY')].summary").value(org.hamcrest.Matchers.contains("the newer money alert", "the older money alert")))
                .andExpect(jsonPath("$[?(@.audience == 'COST')].length()").value(org.hamcrest.Matchers.hasSize(100)))
                .andExpect(jsonPath("$[?(@.summary == 'below cost 119')]").exists())
                .andExpect(jsonPath("$[?(@.summary == 'below cost 19')]").doesNotExist());
        mvc.perform(get(ALERTS).param("state", "ALL").with(SALES)).andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].summary").value("an ended money alert")).andExpect(jsonPath("$[0].resolvedHow").value("PAID"));
    }

    @Test
    @DisplayName("money recorded whose sale was never heard to complete stays in the default list for thirty days after its alert closed by the clock")
    void aStuckAlertThatClosedUnansweredIsStillInTheDefaultList() throws Exception {
        alert("SETTLEMENT_STUCK", UUID.randomUUID().toString(), "never answered", T.minus(Duration.ofDays(20)), "RESOLVED", "BY_CLOCK");
        alert("SETTLEMENT_STUCK", UUID.randomUUID().toString(), "never answered, long ago", T.minus(Duration.ofDays(50)), "RESOLVED", "BY_CLOCK");
        alert("SETTLEMENT_STUCK", UUID.randomUUID().toString(), "answered", T.minus(Duration.ofDays(2)), "RESOLVED", "PAID");
        alert("PAYMENT_TO_CONFIRM", UUID.randomUUID().toString(), "a claim that lapsed", T.minus(Duration.ofDays(2)), "RESOLVED", "BY_CLOCK");

        mvc.perform(get(ALERTS).with(SALES)).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].summary").value("never answered")).andExpect(jsonPath("$[0].resolvedHow").value("BY_CLOCK"));
    }

    @Test
    @DisplayName("an alert that says the system needs attention ends with who to turn to, as configured NOW; and no reply carries a mailbox")
    void theSupportLineIsTheOneConfiguredNow() throws Exception {
        UUID stuck = alert("SETTLEMENT_STUCK", UUID.randomUUID().toString(), "stuck", T, "OPEN", null);
        alert("PAYMENT_TO_CONFIRM", UUID.randomUUID().toString(), "a claim", T.plusSeconds(1), "OPEN", null);
        jdbc.update("UPDATE staff_alert SET emailed_at = ? WHERE id = ?", Timestamp.from(T.plusSeconds(90)), stuck);
        jdbc.update("""
                INSERT INTO notification_log (id, event_type, channel, subject, body, recorded_at, recipient_kind, audience, staff_to, status,
                                              status_at, sent_at, alert_id, alert_notice, alert_tell_no)
                VALUES (?, 'alert.settlement_stuck', 'email', 's', 'b', ?, 'STAFF', 'MONEY', 'alerts-private@oriah.local', 'SENT', ?, ?, ?, 'FIRST', 1)""",
                UUID.randomUUID(), Timestamp.from(T), Timestamp.from(T), Timestamp.from(T), stuck);

        String reply = mvc.perform(get(ALERTS).with(SALES)).andExpect(status().isOk())
                .andExpect(jsonPath("$[1].kind").value("SETTLEMENT_STUCK"))
                .andExpect(jsonPath("$[1].detail").value(endsWith("The words of stuck. Please contact the person who looks after your system.")))
                .andExpect(jsonPath("$[1].emailedAt").value("2026-11-09T07:32:30Z"))
                .andExpect(jsonPath("$[0].detail").value("The words of a claim."))
                .andReturn().getResponse().getContentAsString();
        assertThat(reply).doesNotContain("alerts-private").doesNotContain("@");
    }

    // ---- messages --------------------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("what the customer of one order was told, oldest first, with what became of each; never an owner, an address or an alert")
    void listsWhatTheCustomerOfAnOrderWasTold() throws Exception {
        UUID orderId = UUID.randomUUID();
        message(orderId, "SHOPPER", "kc-alice-private", "placed", T, "NOT_SENT", "BELL_ONLY", false);
        message(orderId, "SHOPPER", "kc-alice-private", "paid", T.plusSeconds(60), "SENT", null, true);
        message(UUID.randomUUID(), "SHOPPER", "kc-bob", "another order", T, "SENT", null, false);
        UUID alert = alert("PAYMENT_TO_CONFIRM", orderId.toString(), "a claim", T, "OPEN", null);
        jdbc.update("""
                INSERT INTO notification_log (id, order_id, event_type, channel, subject, body, recorded_at, recipient_kind, audience, staff_to, status,
                                              status_at, next_attempt_at, send_before, alert_id, alert_notice, alert_tell_no)
                VALUES (?, ?, 'alert.payment_to_confirm', 'email', 'for staff', 'b', ?, 'STAFF', 'MONEY', 'alerts-private@oriah.local', 'RECORDED', ?, ?, ?,
                        ?, 'FIRST', 1)""", UUID.randomUUID(), orderId, Timestamp.from(T), Timestamp.from(T), Timestamp.from(T),
                Timestamp.from(T.plusSeconds(3600)), alert);

        String reply = mvc.perform(get(MESSAGES).param("orderId", orderId.toString()).with(SALES)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].subject").value("placed")).andExpect(jsonPath("$[0].status").value("NOT_SENT"))
                .andExpect(jsonPath("$[0].statusReason").value("BELL_ONLY")).andExpect(jsonPath("$[0].recipientKind").value("SHOPPER"))
                .andExpect(jsonPath("$[1].subject").value("paid")).andExpect(jsonPath("$[1].status").value("SENT"))
                .andExpect(jsonPath("$[1].shortForm").value(true)).andExpect(jsonPath("$[1].sentAt").value("2026-11-09T07:32:05Z"))
                .andExpect(jsonPath("$[1].body").value("the words")).andExpect(jsonPath("$[1].orderId").value(orderId.toString()))
                .andReturn().getResponse().getContentAsString();
        assertThat(reply).doesNotContain("kc-alice-private").doesNotContain("alerts-private").doesNotContain("for staff").doesNotContain("keycloakId");

        mvc.perform(get(MESSAGES).param("orderId", UUID.randomUUID().toString()).with(SALES)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get(MESSAGES).param("orderId", orderId.toString()).with(OWNER)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("the work list: the words waiting to be sent by hand to customers who ordered by phone, newest first, from the last fourteen days")
    void listsTheWordsWaitingToBeSentNewestFirst() throws Exception {
        UUID older = UUID.randomUUID();
        UUID newer = UUID.randomUUID();
        message(older, "CALLER", null, "Payment received", T.minus(Duration.ofDays(2)), "BY_HAND", null, false);
        message(newer, "CALLER", null, "Ready to collect", T.minus(Duration.ofHours(1)), "BY_HAND", null, false);
        message(UUID.randomUUID(), "CALLER", null, "long ago", T.minus(Duration.ofDays(15)), "BY_HAND", null, false);
        message(UUID.randomUUID(), "SHOPPER", "kc-alice", "not by hand", T, "SENT", null, false);

        mvc.perform(get(MESSAGES).param("status", "BY_HAND").with(SALES)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].subject").value("Ready to collect")).andExpect(jsonPath("$[0].orderId").value(newer.toString()))
                .andExpect(jsonPath("$[0].channel").value("by-hand")).andExpect(jsonPath("$[0].recipientKind").value("CALLER"))
                .andExpect(jsonPath("$[1].subject").value("Payment received"));
        mvc.perform(get(MESSAGES).param("status", "BY_HAND").param("since", T.minus(Duration.ofHours(3)).toString()).with(SALES))
                .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get(MESSAGES).param("status", "BY_HAND").param("since", T.minus(Duration.ofDays(30)).toString()).with(SALES))
                .andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    @DisplayName("the messages read needs an order or the work list, not neither, not both, and no other status")
    void needsAnOrderIdOrAStatus() throws Exception {
        mvc.perform(get(MESSAGES).with(SALES)).andExpect(status().isBadRequest());
        mvc.perform(get(MESSAGES).param("orderId", UUID.randomUUID().toString()).param("status", "BY_HAND").with(SALES)).andExpect(status().isBadRequest());
        mvc.perform(get(MESSAGES).param("status", "SENT").with(SALES)).andExpect(status().isBadRequest());
        mvc.perform(get(MESSAGES).param("orderId", "not-an-id").with(SALES)).andExpect(status().isBadRequest());
    }
}

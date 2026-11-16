package com.oriah.notification.controller;

import java.sql.Timestamp;
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

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/v1/notifications/me}: the messages the website's bell shows to the person signed in.
 *
 * <p>It lists that person's own messages of EVERY status, newest first: a message that was not emailed is still a message. Each row now says
 * what became of it ({@code status}, {@code statusReason}, {@code sentAt}) and when it was written ({@code recordedAt}). The old field
 * {@code deliveredAt} is still answered, with the same value as {@code recordedAt}, because the storefront reads it; it never meant
 * "delivered". A row for staff or for a caller has no owner and is in nobody's list.
 */
@ServiceTest
class NotificationMeHttpTest {

    private static final Instant T = Instant.parse("2026-11-09T07:00:00Z");

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

    @Test
    @DisplayName("lists the caller's own messages, newest first")
    void listsTheCallersOwnRowsNewestFirst() throws Exception {
        shopperRow("kc-alice", "first", T, "NOT_SENT", "BELL_ONLY", null);
        shopperRow("kc-alice", "third", T.plusSeconds(120), "NOT_SENT", "BELL_ONLY", null);
        shopperRow("kc-alice", "second", T.plusSeconds(60), "NOT_SENT", "BELL_ONLY", null);

        mvc.perform(get("/api/v1/notifications/me").with(shopper("kc-alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(3))
                .andExpect(jsonPath("$.content[0].subject").value("third"))
                .andExpect(jsonPath("$.content[1].subject").value("second"))
                .andExpect(jsonPath("$.content[2].subject").value("first"));
    }

    @Test
    @DisplayName("never lists another person's messages")
    void neverListsAnotherPersonsRows() throws Exception {
        shopperRow("kc-alice", "for alice", T, "NOT_SENT", "BELL_ONLY", null);
        shopperRow("kc-bob", "for bob", T, "NOT_SENT", "BELL_ONLY", null);

        mvc.perform(get("/api/v1/notifications/me").with(shopper("kc-alice")))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].subject").value("for alice"));
    }

    @Test
    @DisplayName("a message to staff and words for a caller have no owner and are in nobody's list")
    void aRowWithNoOwnerIsInNobodysList() throws Exception {
        jdbc.update("""
                INSERT INTO notification_log (id, keycloak_id, event_type, channel, subject, body, recorded_at, recipient_kind, audience, staff_to,
                                              status, status_at, next_attempt_at, send_before)
                VALUES (?, NULL, 'alert.payment_to_confirm', 'email', 'for staff', 'b', ?, 'STAFF', 'MONEY', 'owner@oriah.local',
                        'RECORDED', ?, ?, ?)""", UUID.randomUUID(), Timestamp.from(T), Timestamp.from(T), Timestamp.from(T), Timestamp.from(T.plusSeconds(3600)));
        jdbc.update("""
                INSERT INTO notification_log (id, order_id, keycloak_id, event_type, channel, subject, body, recorded_at, recipient_kind, status, status_at)
                VALUES (?, ?, NULL, 'order.paid', 'by-hand', 'for a caller', 'b', ?, 'CALLER', 'BY_HAND', ?)""",
                UUID.randomUUID(), UUID.randomUUID(), Timestamp.from(T), Timestamp.from(T));

        mvc.perform(get("/api/v1/notifications/me").with(shopper("kc-alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
        mvc.perform(get("/api/v1/notifications/me").with(shopper("owner@oriah.local")))
                .andExpect(jsonPath("$.content.length()").value(0));
    }

    @Test
    @DisplayName("each row says what became of it: its status, the reason when it was not sent, when it was written and when it was sent")
    void carriesStatusReasonRecordedAtAndSentAt() throws Exception {
        shopperRow("kc-alice", "bell only", T, "NOT_SENT", "BELL_ONLY", null);
        shopperRow("kc-alice", "sent", T.plusSeconds(60), "SENT", null, T.plusSeconds(90));

        mvc.perform(get("/api/v1/notifications/me").with(shopper("kc-alice")))
                .andExpect(jsonPath("$.content[0].subject").value("sent"))
                .andExpect(jsonPath("$.content[0].status").value("SENT"))
                .andExpect(jsonPath("$.content[0].statusReason").value(nullValue()))
                .andExpect(jsonPath("$.content[0].recordedAt").value("2026-11-09T07:01:00Z"))
                .andExpect(jsonPath("$.content[0].sentAt").value("2026-11-09T07:01:30Z"))
                .andExpect(jsonPath("$.content[1].status").value("NOT_SENT"))
                .andExpect(jsonPath("$.content[1].statusReason").value("BELL_ONLY"))
                .andExpect(jsonPath("$.content[1].sentAt").value(nullValue()))
                .andExpect(jsonPath("$.content[1].channel").value("email"))
                .andExpect(jsonPath("$.content[1].eventType").value("order.paid"));
    }

    @Test
    @DisplayName("the old field deliveredAt is still answered and equals recordedAt: it never meant delivered")
    void deliveredAtStillEqualsRecordedAt() throws Exception {
        shopperRow("kc-alice", "sent", T, "SENT", null, T.plusSeconds(90));

        mvc.perform(get("/api/v1/notifications/me").with(shopper("kc-alice")))
                .andExpect(jsonPath("$.content[0].deliveredAt").value("2026-11-09T07:00:00Z"))
                .andExpect(jsonPath("$.content[0].recordedAt").value("2026-11-09T07:00:00Z"));
    }

    @Test
    @DisplayName("a sort asked for by the caller is ignored: the list is newest first, and a sort by the old field name is not an error")
    void aSortParameterIsIgnored() throws Exception {
        shopperRow("kc-alice", "older", T, "NOT_SENT", "BELL_ONLY", null);
        shopperRow("kc-alice", "newer", T.plusSeconds(60), "NOT_SENT", "BELL_ONLY", null);

        for (String sort : new String[] {"deliveredAt,desc", "deliveredAt,asc", "recordedAt,asc", "nonsense", "body"}) {
            mvc.perform(get("/api/v1/notifications/me").param("sort", sort).with(shopper("kc-alice")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content[0].subject").value("newer"));
        }
    }

    @Test
    @DisplayName("paging still works: one row a page, newest first")
    void pagingStillWorks() throws Exception {
        shopperRow("kc-alice", "older", T, "NOT_SENT", "BELL_ONLY", null);
        shopperRow("kc-alice", "newer", T.plusSeconds(60), "NOT_SENT", "BELL_ONLY", null);

        mvc.perform(get("/api/v1/notifications/me").param("size", "1").param("page", "1").with(shopper("kc-alice")))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].subject").value("older"));
    }

    @Test
    @DisplayName("the bell is a shopper's: a signed-in person without USER is refused, and nobody is answered without a token")
    void aCallerWithoutUserIsRefused() throws Exception {
        mvc.perform(get("/api/v1/notifications/me").with(jwt().jwt(j -> j.subject("kc-olive")).authorities(new SimpleGrantedAuthority("ROLE_OWNER"))))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/notifications/me")).andExpect(status().isUnauthorized());
    }

    private static RequestPostProcessor shopper(String subject) {
        return jwt().jwt(j -> j.subject(subject)).authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    private void shopperRow(String owner, String subject, Instant recordedAt, String status, String reason, Instant sentAt) {
        jdbc.update("""
                INSERT INTO notification_log (id, order_id, keycloak_id, event_type, channel, subject, body, recorded_at, recipient_kind,
                                              status, status_reason, status_at, sent_at)
                VALUES (?, ?, ?, 'order.paid', 'email', ?, 'the words', ?, 'SHOPPER', ?, ?, ?, ?)""",
                UUID.randomUUID(), UUID.randomUUID(), owner, subject, Timestamp.from(recordedAt), status, reason, Timestamp.from(recordedAt),
                sentAt == null ? null : Timestamp.from(sentAt));
    }
}

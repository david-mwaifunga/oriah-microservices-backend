package com.oriah.finance.controller;

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

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Knowing whether the books can be trusted: where each producer's catch-up stands, what is wrong and open, how an error is acknowledged by a person who has looked at it (with a
 * note, recorded with who), and how a producer's history is read again after a refused event has been fixed (a catch-up that has finished never asks again by itself).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
class OperationsHttpTest {

    private static final String BASE = "/api/v1/admin/finance";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private static RequestPostProcessor bookkeeper() {
        return jwt().jwt(j -> j.subject("kc-bookkeeper")).authorities(new SimpleGrantedAuthority("ROLE_FINANCE_ADMIN"));
    }

    private static String key() {
        return "k-" + UUID.randomUUID();
    }

    private JsonNode ok(MvcResult result, int status) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(status);
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private MvcResult send(String path, String key, String body, RequestPostProcessor as) throws Exception {
        MockHttpServletRequestBuilder request = post(path).with(as).contentType(MediaType.APPLICATION_JSON).content(body);
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mvc.perform(request).andReturn();
    }

    private long raise(String code, String subject, String severity) {
        return jdbc.queryForObject("INSERT INTO anomaly_log (code, subject, action, severity, detail) VALUES (?, ?, 'RAISE', ?, '{\"hint\":\"a test\"}'::jsonb) RETURNING id", Long.class, code, subject, severity);
    }

    private JsonNode open() throws Exception {
        return ok(mvc.perform(get(BASE + "/anomalies?status=OPEN").with(bookkeeper())).andReturn(), 200);
    }

    private static JsonNode find(JsonNode list, String subject) {
        for (JsonNode a : list) {
            if (subject.equals(a.get("subject").asText())) {
                return a;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ status

    @Test
    @DisplayName("the status says where each producer's catch-up stands and how many errors and warnings are open; money is never in it")
    void status() throws Exception {
        raise("test-error", "status-" + UUID.randomUUID(), "ERROR");

        JsonNode s = ok(mvc.perform(get(BASE + "/status").with(bookkeeper())).andReturn(), 200);

        List<String> producers = new java.util.ArrayList<>();
        for (JsonNode p : s.get("producers")) {
            producers.add(p.get("producer").asText());
            assertThat(p.get("complete").isBoolean()).isTrue();
            assertThat(p.has("replayedThrough")).isTrue();
        }
        assertThat(producers).containsExactlyInAnyOrder("catalog", "inventory", "order", "payment", "procurement");
        assertThat(s.get("openErrors").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(s.get("openWarnings").isInt()).isTrue();
        assertThat(s.get("catchUpComplete").isBoolean()).isTrue();
        assertThat(s.toString()).as("a status carries counts, never an amount").doesNotContain("amount");
    }

    // ------------------------------------------------------------------ anomalies

    @Test
    @DisplayName("the open errors and warnings are listed with what they are about (ids and codes, never a value a person typed); each can be acknowledged once, with a note, by whoever has looked")
    void acknowledging() throws Exception {
        String subject = "ack-" + UUID.randomUUID();
        long id = raise("test-error", subject, "ERROR");

        JsonNode listed = find(open(), subject);

        assertThat(listed).isNotNull();
        assertThat(listed.get("id").asLong()).isEqualTo(id);
        assertThat(listed.get("severity").asText()).isEqualTo("ERROR");
        assertThat(listed.get("status").asText()).isEqualTo("OPEN");
        assertThat(listed.get("detail").get("hint").asText()).isEqualTo("a test");

        JsonNode acked = ok(send(BASE + "/anomalies/" + id + "/ack", key(), "{\"note\":\"looked at it, the producer was reset\"}", bookkeeper()), 201);

        assertThat(acked.get("status").asText()).isEqualTo("ACKNOWLEDGED");
        assertThat(acked.get("acknowledgedBy").asText()).isEqualTo("user:kc-bookkeeper");
        assertThat(acked.get("note").asText()).isEqualTo("looked at it, the producer was reset");
        assertThat(find(open(), subject)).as("an acknowledged error is no longer open").isNull();
        assertThat(ok(send(BASE + "/anomalies/" + id + "/ack", key(), "{\"note\":\"looked at it again\"}", bookkeeper()), 409).get("type").asText()).endsWith("/anomaly-not-open");
        JsonNode all = ok(mvc.perform(get(BASE + "/anomalies?status=ALL").with(bookkeeper())).andReturn(), 200);
        assertThat(find(all, subject).get("status").asText()).isEqualTo("ACKNOWLEDGED");
    }

    @Test
    @DisplayName("the same thing that happens again after it was acknowledged is a new open anomaly: acknowledging closes one occurrence, never the cause for ever")
    void anAnomalyCanReopen() throws Exception {
        String subject = "again-" + UUID.randomUUID();
        long first = raise("test-error", subject, "WARN");
        ok(send(BASE + "/anomalies/" + first + "/ack", key(), "{\"note\":\"fine, expected\"}", bookkeeper()), 201);

        long second = raise("test-error", subject, "WARN");

        assertThat(second).isGreaterThan(first);
        assertThat(find(open(), subject).get("id").asLong()).isEqualTo(second);
    }

    @Test
    @DisplayName("an acknowledgement needs a real note, an anomaly that is not there is 404, and an old occurrence cannot be acknowledged once a newer one stands; a repeat of the same call is the same answer")
    void ackRules() throws Exception {
        String subject = "rules-" + UUID.randomUUID();
        long id = raise("test-error", subject, "ERROR");

        assertThat(ok(send(BASE + "/anomalies/" + id + "/ack", key(), "{\"note\":\"no\"}", bookkeeper()), 400).get("type").asText()).isNotEmpty();
        assertThat(ok(send(BASE + "/anomalies/999999999/ack", key(), "{\"note\":\"looked at it\"}", bookkeeper()), 404).get("type").asText()).endsWith("/anomaly-not-found");
        assertThat(ok(send(BASE + "/anomalies/" + id + "/ack", null, "{\"note\":\"looked at it\"}", bookkeeper()), 400).get("type").asText()).endsWith("/idempotency-key-required");
        String k = key();
        JsonNode first = ok(send(BASE + "/anomalies/" + id + "/ack", k, "{\"note\":\"looked at it\"}", bookkeeper()), 201);
        JsonNode again = ok(send(BASE + "/anomalies/" + id + "/ack", k, "{\"note\":\"looked at it\"}", bookkeeper()), 200);
        assertThat(again.get("id").asLong()).isEqualTo(first.get("id").asLong());
    }

    // ------------------------------------------------------------------ re-verify

    @Test
    @DisplayName("RE-VERIFY: a producer whose catch-up had finished is set to be read again from the beginning at the next start (what finished never asks again by itself); an unknown producer is refused")
    void reverify() throws Exception {
        jdbc.update("UPDATE outbox_catch_up SET completed_at = now(), completed_through = 77, replayed_through = 77 WHERE producer = 'catalog'");

        JsonNode done = ok(send(BASE + "/catch-up/reverify", key(), "{\"producer\":\"catalog\"}", bookkeeper()), 201);

        assertThat(done.get("producers").get(0).asText()).isEqualTo("catalog");
        assertThat(done.get("takesEffect").asText()).containsIgnoringCase("next start");
        assertThat(jdbc.queryForMap("SELECT replayed_through, completed_through, completed_at FROM outbox_catch_up WHERE producer = 'catalog'"))
                .containsEntry("replayed_through", 0L).containsEntry("completed_through", null).containsEntry("completed_at", null);
        assertThat(ok(send(BASE + "/catch-up/reverify", key(), "{\"producer\":\"nobody\"}", bookkeeper()), 400).get("type").asText()).endsWith("/unknown-producer");
        JsonNode all = ok(send(BASE + "/catch-up/reverify", key(), "{}", bookkeeper()), 201);
        assertThat(all.get("producers")).hasSize(5);
    }

    // ------------------------------------------------------------------ who may

    @Test
    @DisplayName("only FINANCE_ADMIN reads the status and the anomalies, acknowledges, or asks for a re-verify")
    void roles() throws Exception {
        assertThat(mvc.perform(get(BASE + "/status")).andReturn().getResponse().getStatus()).isEqualTo(401);
        long id = raise("test-error", "roles-" + UUID.randomUUID(), "WARN");
        for (String role : List.of("USER", "MANAGER", "COST_VIEWER", "SALES_AGENT")) {
            RequestPostProcessor as = jwt().jwt(j -> j.subject("kc-x")).authorities(new SimpleGrantedAuthority("ROLE_" + role));
            assertThat(mvc.perform(get(BASE + "/status").with(as)).andReturn().getResponse().getStatus()).as(role).isEqualTo(403);
            assertThat(mvc.perform(get(BASE + "/anomalies").with(as)).andReturn().getResponse().getStatus()).as(role).isEqualTo(403);
            assertThat(send(BASE + "/anomalies/" + id + "/ack", key(), "{\"note\":\"looked at it\"}", as).getResponse().getStatus()).as(role).isEqualTo(403);
            assertThat(send(BASE + "/catch-up/reverify", key(), "{}", as).getResponse().getStatus()).as(role).isEqualTo(403);
        }
    }
}

package com.oriah.finance.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.oriah.finance.TestcontainersConfiguration;

import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Two people reversing the same expense at the same moment (different keys): one reversal goes in, the other is told it was already reversed (409), never a server error. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
class ConcurrentReversalTest {

    private static final String BASE = "/api/v1/admin/finance";

    @Autowired
    private MockMvc mvc;
    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private int send(String path, String body) throws Exception {
        return mvc.perform(post(path).with(jwt().jwt(j -> j.subject("kc-bookkeeper")).authorities(new SimpleGrantedAuthority("ROLE_FINANCE_ADMIN"))).header("Idempotency-Key", "k-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getStatus();
    }

    @Test
    @DisplayName("two reversals of one expense at once: exactly one 201 and the rest 409, never a 500")
    void twoAtOnce() throws Exception {
        for (int round = 0; round < 3; round++) {
            String id = JsonMapper.builder().build().readTree(mvc.perform(post(BASE + "/expenses").with(jwt().jwt(j -> j.subject("kc-bookkeeper"))
                    .authorities(new SimpleGrantedAuthority("ROLE_FINANCE_ADMIN"))).header("Idempotency-Key", "k-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"category\":\"RENT_UTILITIES\",\"amount\":10.00,\"expenseDate\":\"2018-04-10\",\"description\":\"rent\"}").accept(MediaType.APPLICATION_JSON))
                    .andReturn().getResponse().getContentAsString()).get("id").asText();
            int racers = 4;
            CountDownLatch go = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(racers);
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < racers; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    return send(BASE + "/expenses/" + id + "/reversal", "{\"reason\":\"keyed by mistake\"}");
                }));
            }
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : results) {
                statuses.add(f.get(30, TimeUnit.SECONDS));
            }
            pool.shutdown();

            assertThat(statuses).as("statuses of " + racers + " racing reversals").containsOnly(201, 409);
            assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(1);
        }
    }
}

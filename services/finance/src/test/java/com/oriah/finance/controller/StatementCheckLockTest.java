package com.oriah.finance.controller;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

import com.oriah.finance.TestcontainersConfiguration;
import com.oriah.finance.dto.StatementCheckRequest;
import com.oriah.finance.ledger.Locks;
import com.oriah.finance.service.StatementCheckService;

import static org.assertj.core.api.Assertions.assertThat;

/** Every writer takes the ledger lock shared, so the person reporting a month (exclusive) waits for it: a statement check typed while a month is being reported waits its turn. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
class StatementCheckLockTest {

    @Autowired
    private StatementCheckService checks;
    @Autowired
    private Locks locks;
    @Autowired
    private TransactionTemplate transactions;
    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("a statement check waits while a month is being reported, and goes in as soon as the reporter is done")
    void waitsForTheReporter() throws Exception {
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> reporter = CompletableFuture.runAsync(() -> transactions.executeWithoutResult(status -> {
            locks.issuer();
            holding.countDown();
            try {
                release.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        assertThat(holding.await(10, TimeUnit.SECONDS)).isTrue();

        CompletableFuture<Object> typed = CompletableFuture.supplyAsync(() -> checks.add("user:kc-bookkeeper", new StatementCheckRequest("2018-03", "MTN", new java.math.BigDecimal("1.00"), null)));
        Thread.sleep(700);
        assertThat(typed.isDone()).as("the statement check must wait for the reporter's exclusive lock").isFalse();

        release.countDown();
        reporter.get(10, TimeUnit.SECONDS);
        assertThat(typed.get(10, TimeUnit.SECONDS)).isNotNull();
    }
}

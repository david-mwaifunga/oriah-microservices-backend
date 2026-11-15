package com.oriah.finance.events;

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
import com.oriah.finance.ledger.Locks;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Writing down that an event was refused is a write like any other: while a month is being reported (the ledger lock held exclusively) it waits, so an error is never stamped before
 * the report's moment and committed after it, which would let a month be reported as complete with an event missing.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
class RefusalLockTest {

    @Autowired
    private RefusalRecorder refusals;
    @Autowired
    private Locks locks;
    @Autowired
    private TransactionTemplate transactions;
    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @Test
    @DisplayName("a refusal is written down after the reporter is done, not while the month is being reported")
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

        CompletableFuture<Void> written = CompletableFuture.runAsync(() -> refusals.refused("00000000-0000-0000-0000-0000000000aa", "order.paid", "order", new IllegalStateException("x")));
        Thread.sleep(700);
        assertThat(written.isDone()).as("the refusal must wait for the reporter's exclusive lock").isFalse();

        release.countDown();
        reporter.get(10, TimeUnit.SECONDS);
        written.get(10, TimeUnit.SECONDS);
    }
}

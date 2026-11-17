package com.oriah.payment.provider;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * ZynlePay as a test scripts it: what it answers to "collect this" and to "what became of it", and a record of what it was asked. It stands in for
 * the port only. The real adapter is tested against real sockets ({@code ZynlePayAdapterTest}) and against the stand-in inside the service
 * ({@code ZynlePaySimulatorHttpTest}); here the subject is what payment does with each answer.
 */
public class ScriptedProvider implements PaymentProvider {

    /** One "collect this" as payment sent it. */
    public record Asked(String kind, String referenceNo, String msisdnDigits, BigDecimal amount, String currency, CardPayer payer) {
    }

    private final List<Asked> asked = new CopyOnWriteArrayList<>();
    private final Map<String, Status> statuses = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> statusCalls = new ConcurrentHashMap<>();
    private volatile Push nextPush;

    /** What the next "collect this" answers; after it, the usual "accepted" again. */
    public void pushAnswers(Push push) {
        this.nextPush = push;
    }

    /** What "what became of it" answers for this reference from now on (until then: still deciding). */
    public void statusOf(String referenceNo, Status status) {
        statuses.put(referenceNo, status);
    }

    public List<Asked> asked() {
        return asked;
    }

    public List<Asked> askedFor(String orderDescriptionOrReference) {
        return asked.stream().filter(a -> a.referenceNo().equals(orderDescriptionOrReference)).toList();
    }

    public int statusCalls(String referenceNo) {
        AtomicInteger count = statusCalls.get(referenceNo);
        return count == null ? 0 : count.get();
    }

    @Override
    public Push startMobileMoney(String referenceNo, String msisdnDigits, BigDecimal amount, String currency) {
        asked.add(new Asked("MOBILE_MONEY", referenceNo, msisdnDigits, amount, currency, null));
        return answer(new Accepted("120", "TX" + referenceNo.substring(2, 10), "OP" + referenceNo.substring(2, 10), "MTN"));
    }

    @Override
    public Push startCard(String referenceNo, BigDecimal amount, String currency, String description, CardPayer payer) {
        asked.add(new Asked("CARD", referenceNo, null, amount, currency, payer));
        return answer(new Redirect("120", java.net.URI.create("http://localhost:8085/internal/zynlepay-sim/card/" + referenceNo), "TX" + referenceNo.substring(2, 10)));
    }

    @Override
    public Status status(String referenceNo) {
        statusCalls.computeIfAbsent(referenceNo, r -> new AtomicInteger()).incrementAndGet();
        return statuses.getOrDefault(referenceNo, new Pending("990"));
    }

    private Push answer(Push usual) {
        Push scripted = nextPush;
        nextPush = null;
        return scripted != null ? scripted : usual;
    }

    /** Puts the scripted ZynlePay in place of the adapter for a test class that imports this. */
    @TestConfiguration
    public static class Config {

        @Bean
        @Primary
        ScriptedProvider scriptedProvider() {
            return new ScriptedProvider();
        }
    }
}

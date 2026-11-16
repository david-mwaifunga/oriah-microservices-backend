package com.oriah.notification.delivery;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Stands in for the mail server in the dispatcher's tests. It accepts everything unless a test queued other answers (one per send, in order),
 * remembers every email it was handed, notes whether a database transaction was open on the calling thread at that moment (there must never be
 * one), and can be made to hold a send until the test lets it go.
 */
public final class RecordingTransport implements MessageTransport {

    private final List<Email> handedOver = new CopyOnWriteArrayList<>();
    private final Deque<SendResult> answers = new ArrayDeque<>();
    private final AtomicInteger probes = new AtomicInteger();
    private volatile boolean sawATransaction;
    private volatile SendResult probeAnswer = SendResult.accepted();
    private volatile CountDownLatch hold;
    private volatile CountDownLatch entered = new CountDownLatch(1);

    /** The next sends get these answers, in order; after them everything is accepted again. */
    public synchronized RecordingTransport thenAnswer(SendResult... results) {
        answers.addAll(List.of(results));
        return this;
    }

    public RecordingTransport probeAnswers(SendResult result) {
        this.probeAnswer = result;
        return this;
    }

    /** Every send from now on waits inside the transport until {@link #letGo()}. */
    public void holdSends() {
        hold = new CountDownLatch(1);
        entered = new CountDownLatch(1);
    }

    public void letGo() {
        hold.countDown();
    }

    /** Waits until a send is inside the transport. */
    public boolean aSendIsInFlight() throws InterruptedException {
        return entered.await(10, TimeUnit.SECONDS);
    }

    public List<Email> handedOver() {
        return List.copyOf(handedOver);
    }

    public int probes() {
        return probes.get();
    }

    public boolean sawATransaction() {
        return sawATransaction;
    }

    @Override
    public SendResult send(Email email) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            sawATransaction = true;
        }
        handedOver.add(email);
        entered.countDown();
        CountDownLatch held = hold;
        if (held != null) {
            try {
                held.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        synchronized (this) {
            return answers.isEmpty() ? SendResult.accepted() : answers.poll();
        }
    }

    @Override
    public SendResult probe() {
        probes.incrementAndGet();
        return probeAnswer;
    }
}

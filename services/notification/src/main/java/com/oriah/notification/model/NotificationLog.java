package com.oriah.notification.model;

import java.time.Instant;
import java.util.UUID;

import com.oriah.jpa.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One message to one person, and what became of it.
 *
 * <p>The row is written by the consumer in the same transaction as the Inbox's "applied" marker, and nothing is sent then. A scheduled sender
 * picks up the rows that are {@link MessageStatus#RECORDED} and moves each to its end: SENT, FAILED, or NOT_SENT with a reason.
 *
 * <p>This class is how a row is READ, and how the consumer writes a customer's row. Every change of status after that, and every row for staff,
 * is written by the sender's own guarded SQL ({@code MessageStore}): a status change must be one statement that also checks nobody else changed
 * the row first, which a loaded entity saved back cannot promise. So there is no setter here.
 *
 * <p>No address is ever on a customer's row. The address is looked up when the message is sent.
 */
@Entity
@Table(name = "notification_log")
public class NotificationLog extends BaseEntity {

    /** The only channel a machine sends on. Words for a person to send are "by-hand". */
    public static final String EMAIL = "email";

    @Id
    @Column(nullable = false)
    private UUID id;

    @Column(name = "order_id")
    private UUID orderId;

    /** The customer whose message this is; null for a caller's and a staff row, which are in nobody's bell. */
    @Column(name = "keycloak_id")
    private String keycloakId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(nullable = false, length = 20)
    private String channel;

    @Column(nullable = false)
    private String subject;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    /** When the row was written. (Until Phase 11 this column was called delivered_at, which it never meant.) */
    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "recipient_kind", nullable = false, length = 10)
    private RecipientKind recipientKind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private MessageStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "status_reason", length = 30)
    private StatusReason statusReason;

    @Column(name = "status_at", nullable = false)
    private Instant statusAt;

    /** Set exactly while the row waits to be sent. */
    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;

    /** The latest moment the message is still worth sending. */
    @Column(name = "send_before")
    private Instant sendBefore;

    /** When the mail server answered that it took the message. */
    @Column(name = "sent_at")
    private Instant sentAt;

    /** True when what was emailed was the short "please sign in" note rather than these words. */
    @Column(name = "short_form", nullable = false)
    private boolean shortForm;

    @Column(name = "event_id")
    private UUID eventId;

    /** The event's place in its order's history (the outbox's own count per order): messages of one order are sent in this order. */
    @Column(name = "event_seq")
    private Long eventSeq;

    /** When the event's producer wrote it down: what "too late to send" is measured from. */
    @Column(name = "event_recorded_at")
    private Instant eventRecordedAt;

    protected NotificationLog() {
    }

    /**
     * A customer's message, as the consumer writes it down.
     *
     * @param notSentBecause null for a message that waits to be emailed; otherwise why it will not be (website only, too late, a step keyed
     *                       long after it happened), and then it is final at once
     * @param sendBefore     the latest moment it is still worth sending; used only when it waits to be emailed
     * @param now            the service's clock
     */
    public static NotificationLog forShopper(UUID orderId, String keycloakId, String eventType, String subject, String body, UUID eventId,
                                             long eventSeq, Instant eventRecordedAt, Instant now, StatusReason notSentBecause,
                                             Instant sendBefore) {
        if (keycloakId == null || keycloakId.isBlank()) {
            throw new IllegalArgumentException("a customer's message needs its owner");
        }
        NotificationLog row = new NotificationLog();
        row.id = UUID.randomUUID();
        row.orderId = orderId;
        row.keycloakId = keycloakId;
        row.eventType = eventType;
        row.channel = EMAIL;
        row.subject = subject;
        row.body = body;
        row.recipientKind = RecipientKind.SHOPPER;
        row.eventId = eventId;
        row.eventSeq = eventSeq;
        row.eventRecordedAt = eventRecordedAt;
        row.recordedAt = now;
        row.statusAt = now;
        if (notSentBecause == null) {
            row.status = MessageStatus.RECORDED;
            row.nextAttemptAt = now;
            row.sendBefore = sendBefore;
        } else {
            row.status = MessageStatus.NOT_SENT;
            row.statusReason = notSentBecause;
        }
        return row;
    }

    public UUID id() { return id; }
    public UUID orderId() { return orderId; }
    public String keycloakId() { return keycloakId; }
    public String eventType() { return eventType; }
    public String channel() { return channel; }
    public String subject() { return subject; }
    public String body() { return body; }
    public Instant recordedAt() { return recordedAt; }
    public RecipientKind recipientKind() { return recipientKind; }
    public MessageStatus status() { return status; }
    public StatusReason statusReason() { return statusReason; }
    public Instant statusAt() { return statusAt; }
    public Instant nextAttemptAt() { return nextAttemptAt; }
    public Instant sendBefore() { return sendBefore; }
    public Instant sentAt() { return sentAt; }
    public boolean shortForm() { return shortForm; }
    public UUID eventId() { return eventId; }
    public Long eventSeq() { return eventSeq; }
    public Instant eventRecordedAt() { return eventRecordedAt; }
}

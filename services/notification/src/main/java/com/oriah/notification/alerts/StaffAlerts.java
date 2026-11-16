package com.oriah.notification.alerts;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.oriah.notification.config.EmailSetup;
import com.oriah.notification.config.NotificationSettings;
import com.oriah.notification.text.MessageTexts;
import com.oriah.notification.text.MessageTexts.Text;

/**
 * The things staff must look at ({@code staff_alert}), and the emails that tell them (STAFF rows of {@code notification_log}, one per mailbox,
 * sent by the dispatcher like any other message).
 *
 * <p><b>An alert is a stored fact first.</b> There is one open row per thing ({@code kind} + {@code subject_id}); the insert that lands is the
 * one that tells, so the same fact arriving twice, or two instances noticing the same thing, tells once. It ends by a fact ({@link #resolve})
 * or by the clock ({@code closes_at}), never by being forgotten.
 *
 * <p><b>"Told" means a mail server took it</b> ({@code emailed_at}, stamped by the dispatcher), never "an email was written down". An open
 * alert nobody was told about, with nothing waiting to go, is written again every {@code alerts.retell-after}: a mail outage, a missing mailbox
 * or the pause switch delays an alert and never swallows it.
 *
 * <p><b>Who is told</b> is decided once at start ({@link EmailSetup}): two lists of shop mailboxes from the environment, MONEY and COST. An
 * address is on a STAFF row and nowhere else; it is never logged.
 *
 * <p>Methods called by a consumer run in its transaction, with the Inbox's "applied" marker. Methods called by the dispatcher each run in a
 * short transaction of their own.
 */
@Component
public class StaffAlerts {

    private static final Logger log = LoggerFactory.getLogger(StaffAlerts.class);
    /** One lock for "is there room in today's payment-claim emails?", so two claims can never both take the last place. */
    private static final long CLAIM_EMAIL_LIMIT_LOCK = 7_311_001L;
    private static final int ALERTS_A_PASS = 50;

    /** What kind of thing an alert is about, and which of the two groups of mailboxes is told. */
    public enum Kind {
        PAYMENT_TO_CONFIRM(false), SETTLEMENT_STUCK(false), VOID_AFTER_SALE(false), CLAIM_EMAIL_LIMIT(false), EMAIL_DOWN(false),
        BELOW_COST(true), BELOW_COST_CHECK_DOWN(true);

        private final boolean cost;

        Kind(boolean cost) {
            this.cost = cost;
        }

        public String audience() {
            return cost ? "COST" : "MONEY";
        }

        /** The type a STAFF row carries: {@code alert.<kind in small letters>}. */
        public String eventType() {
            return "alert." + name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * A new alert.
     *
     * @param subjectId    what it is about: an order id, a confirmation id, a variant id, a day, or a fixed word
     * @param words        its one line (the subject) and its full words (the body)
     * @param tellBefore   the latest moment its emails are still worth sending; null for the general window
     * @param remindAt     when it is told again if still open; null for no reminder
     * @param reminder     the words of that reminder; needed when remindAt is set
     * @param closesAt     when it closes by itself; null only for the kinds notification resolves itself
     * @param emailLimited true for a payment claim raised beyond the day's limit: listed, never emailed
     */
    public record NewAlert(Kind kind, String subjectId, UUID orderId, Text words, Instant tellBefore, Instant remindAt, Text reminder,
                           Instant closesAt, boolean emailLimited) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final EmailSetup setup;
    private final NotificationSettings settings;

    @Autowired
    public StaffAlerts(JdbcTemplate jdbc, PlatformTransactionManager transactions, EmailSetup setup, NotificationSettings settings) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactions);
        this.setup = setup;
        this.settings = settings;
    }

    // ---- raise, tell, resolve --------------------------------------------------------------------------------------------------------------

    /** @return the new alert's id; empty when an open alert about the same thing already stands (and then nobody is told again) */
    public Optional<UUID> raise(NewAlert alert, Instant now) {
        UUID id = UUID.randomUUID();
        int landed = jdbc.update("""
                INSERT INTO staff_alert (id, kind, subject_id, order_id, audience, state, summary, detail, raised_at, email_limited, tell_before,
                                         remind_at, remind_subject, remind_body, closes_at)
                VALUES (?, ?, ?, ?, ?, 'OPEN', ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (kind, subject_id) WHERE state = 'OPEN' DO NOTHING""",
                id, alert.kind().name(), alert.subjectId(), alert.orderId(), alert.kind().audience(), alert.words().subject(), alert.words().body(),
                Timestamp.from(now), alert.emailLimited(), timestamp(alert.tellBefore()), timestamp(alert.remindAt()),
                alert.remindAt() == null ? null : alert.reminder().subject(), alert.remindAt() == null ? null : alert.reminder().body(),
                timestamp(alert.closesAt()));
        return landed == 1 ? Optional.of(id) : Optional.empty();
    }

    /**
     * Writes one notice of an alert as emails: one row per mailbox of the alert's group, each sent (and retried) on its own. With no mailbox
     * configured, one row says so and the alert stands; it is told when a mailbox is given. A second call for the same notice writes nothing.
     *
     * @param tellNo     which telling of this notice (1 for the first)
     * @param dueAt      when the dispatcher may first send it
     * @param tellBefore the latest moment it is still worth sending; null for the general window
     */
    public void tell(UUID alertId, Kind kind, String notice, int tellNo, Text words, Instant dueAt, Instant tellBefore, Instant now) {
        List<String> mailboxes = recipients(kind);
        if (mailboxes.isEmpty()) {
            staffRow(alertId, kind, notice, tellNo, words, null, "NOT_SENT", "NO_RECIPIENT_CONFIGURED", null, null, now);
            return;
        }
        Instant sendBefore = tellBefore != null ? tellBefore : now.plus(settings.send().window());
        for (String mailbox : mailboxes) {
            staffRow(alertId, kind, notice, tellNo, words, mailbox, "RECORDED", null, dueAt, sendBefore, now);
        }
    }

    private void staffRow(UUID alertId, Kind kind, String notice, int tellNo, Text words, String mailbox, String status, String reason,
                          Instant dueAt, Instant sendBefore, Instant now) {
        jdbc.update("""
                INSERT INTO notification_log (id, order_id, event_type, channel, subject, body, recorded_at, recipient_kind, audience, staff_to,
                                              status, status_reason, status_at, next_attempt_at, send_before, alert_id, alert_notice, alert_tell_no,
                                              created_at)
                SELECT ?, a.order_id, ?, 'email', ?, ?, ?, 'STAFF', a.audience, ?, ?, ?, ?, ?, ?, a.id, ?, ?, ?
                  FROM staff_alert a WHERE a.id = ?
                ON CONFLICT DO NOTHING""",
                UUID.randomUUID(), kind.eventType(), words.subject(), words.body(), Timestamp.from(now), mailbox, status, reason,
                Timestamp.from(now), timestamp(dueAt), timestamp(sendBefore), notice, tellNo, Timestamp.from(now), alertId);
    }

    /** Ends the open alert about this thing, if there is one. @return whether one was open */
    public boolean resolve(Kind kind, String subjectId, String how, Instant now) {
        if (kind == Kind.PAYMENT_TO_CONFIRM && !"MONEY_RECORDED".equals(how)) {
            // The order's real ending (paid, expired, cancelled). It ends the open claim alert AND gives a claim alert that had stepped aside
            // when money was recorded its real ending, in ONE statement: if the other consumer is stepping the alert aside at this very
            // moment, this statement waits for it and then still matches the row, whichever of the two states it finds.
            return jdbc.update("""
                    UPDATE staff_alert SET state = 'RESOLVED', resolved_at = COALESCE(resolved_at, ?), resolved_how = ?, remind_at = NULL
                     WHERE kind = 'PAYMENT_TO_CONFIRM' AND subject_id = ?
                       AND (state = 'OPEN' OR (state = 'RESOLVED' AND resolved_how = 'MONEY_RECORDED'))""",
                    Timestamp.from(now), how, subjectId) > 0;
        }
        return jdbc.update("""
                UPDATE staff_alert SET state = 'RESOLVED', resolved_at = ?, resolved_how = ?, remind_at = NULL
                 WHERE kind = ? AND subject_id = ? AND state = 'OPEN'""", Timestamp.from(now), how, kind.name(), subjectId) == 1;
    }

    /**
     * An alert as stored.
     *
     * @param resolvedHow how it ended; null while open
     */
    public record Stored(UUID id, String state, String resolvedHow) {

        public boolean open() {
            return "OPEN".equals(state);
        }
    }

    /** The newest alert about this thing, open or ended; empty when there never was one. */
    public Optional<Stored> newest(Kind kind, String subjectId) {
        return jdbc.query("""
                SELECT id, state, resolved_how FROM staff_alert WHERE kind = ? AND subject_id = ?
                 ORDER BY raised_at DESC, (state = 'OPEN') DESC LIMIT 1""",
                (row, n) -> new Stored(row.getObject(1, UUID.class), row.getString(2), row.getString(3)), kind.name(), subjectId).stream().findFirst();
    }

    /** Gives an alert that closed by the clock its real answer, heard later: it leaves the "never answered" list. */
    public void relabel(UUID alertId, String how) {
        jdbc.update("UPDATE staff_alert SET resolved_how = ? WHERE id = ? AND state = 'RESOLVED'", how, alertId);
    }

    /** Whether this notice of the alert was ever written. */
    public boolean said(UUID alertId, String notice) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM notification_log WHERE alert_id = ? AND alert_notice = ?)", Boolean.class, alertId, notice));
    }

    /**
     * Whether staff may have been told of this alert: some notice of it was at least tried. A send that is in flight right now counts (its
     * try was counted when the row was taken), so an answer that arrives during the send is still followed by "sorted".
     */
    public boolean aNoticeMayHaveGoneOut(UUID alertId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM notification_log
                                WHERE alert_id = ? AND alert_notice IN ('FIRST', 'AGAIN', 'REMINDER', 'CLOSING') AND attempts > 0)""",
                Boolean.class, alertId));
    }

    /**
     * Opens "a customer says they have paid" again for an order whose recorded money ended held or cancelled, while its hold still runs.
     * Staff were told of this claim once, so no first email is written again; the list and the reminder (if it has not gone) carry it.
     */
    public void reopenClaim(UUID orderId, Instant now) {
        record Old(UUID id, Instant tellBefore, int remindersSent, boolean limited, boolean hasReminderWords) {
        }
        List<Old> newest = jdbc.query("""
                SELECT id, tell_before, reminders_sent, email_limited, remind_subject IS NOT NULL AS has_words FROM staff_alert
                 WHERE kind = 'PAYMENT_TO_CONFIRM' AND subject_id = ? AND state = 'RESOLVED' AND resolved_how = 'MONEY_RECORDED'
                   AND NOT EXISTS (SELECT 1 FROM staff_alert o WHERE o.kind = 'PAYMENT_TO_CONFIRM' AND o.subject_id = ? AND o.state = 'OPEN')
                 ORDER BY raised_at DESC LIMIT 1""",
                (row, n) -> new Old(row.getObject("id", UUID.class), instant(row.getTimestamp("tell_before")), row.getInt("reminders_sent"),
                        row.getBoolean("email_limited"), row.getBoolean("has_words")), orderId.toString(), orderId.toString());
        if (newest.isEmpty() || newest.getFirst().tellBefore() == null || !now.isBefore(newest.getFirst().tellBefore())) {
            return;
        }
        Old old = newest.getFirst();
        NotificationSettings.Hold hold = settings.alerts().hold();
        Instant remindAt = old.remindersSent() > 0 || old.limited() || !old.hasReminderWords() ? null
                : new HoldRemindTime(hold.remindBefore(), hold.quietFrom(), hold.quietUntil(), settings.zone()).of(old.tellBefore(), now).orElse(null);
        jdbc.update("""
                INSERT INTO staff_alert (id, kind, subject_id, order_id, audience, state, summary, detail, raised_at, emailed_at, email_limited,
                                         tell_before, remind_at, remind_subject, remind_body, reminders_sent, closes_at)
                SELECT ?, kind, subject_id, order_id, audience, 'OPEN', summary, detail, ?, emailed_at, email_limited, tell_before, ?,
                       remind_subject, remind_body, reminders_sent, closes_at
                  FROM staff_alert WHERE id = ?
                ON CONFLICT (kind, subject_id) WHERE state = 'OPEN' DO NOTHING""", UUID.randomUUID(), Timestamp.from(now), timestamp(remindAt),
                old.id());
    }

    /**
     * Raises an alert and tells its group at once, in one transaction. For the scheduled jobs, which have no transaction of their own.
     *
     * @return whether this call raised it (false when an open alert about the same thing already stands)
     */
    public boolean raiseAndTell(NewAlert alert, Instant now) {
        return Boolean.TRUE.equals(transaction.execute(tx -> {
            Optional<UUID> id = raise(alert, now);
            id.ifPresent(raised -> tell(raised, alert.kind(), "FIRST", 1, alert.words(), now, alert.tellBefore(), now));
            return id.isPresent();
        }));
    }

    /** A mail server took an email about this alert: from now on staff have been told. */
    public void accepted(UUID alertId, Instant now) {
        jdbc.update("UPDATE staff_alert SET emailed_at = COALESCE(emailed_at, ?) WHERE id = ?", Timestamp.from(now), alertId);
    }

    private List<String> recipients(Kind kind) {
        return "COST".equals(kind.audience()) ? setup.costRecipients() : setup.moneyRecipients();
    }

    // ---- a customer says they have paid ----------------------------------------------------------------------------------------------------

    /**
     * A customer has told the shop they paid ({@code order.payment_submitted}). Staff are told at once and reminded once before the pieces are
     * released. Called by the consumer, in its transaction.
     *
     * <p>Anyone can open an account and say they have paid, so the number of these alerts EMAILED in any 24 hours is limited
     * ({@code alerts.payment-to-confirm.max-alerts-emailed-per-day}). The place is taken here, when the alert is raised, under one lock, so
     * two claims can never both take the last one. Beyond the limit the alert is still raised and listed, it is never emailed, and ONE alert
     * of its own says so by email. No other kind of alert is ever limited.
     *
     * @param holdExpiresAt  when the pieces stop being held for the customer
     * @param factRecordedAt when order wrote the claim down; a claim read long after the fact is listed and emails nobody at once
     */
    public void claimReceived(UUID orderId, String method, Instant holdExpiresAt, Instant factRecordedAt, Instant now) {
        if (!now.isBefore(holdExpiresAt)) {
            // The hold is over: there is nothing left to confirm in time, and order has ended or will end the order by itself.
            return;
        }
        String subject = orderId.toString();
        if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM staff_alert WHERE kind = 'PAYMENT_TO_CONFIRM' AND subject_id = ?)",
                Boolean.class, subject))) {
            // The same order's claim again (the same claim repeated): staff were told once.
            return;
        }
        jdbc.query("SELECT pg_advisory_xact_lock(?)", row -> { }, CLAIM_EMAIL_LIMIT_LOCK);
        int limit = settings.alerts().paymentToConfirm().maxAlertsEmailedPerDay();
        Integer emailedToday = jdbc.queryForObject("""
                SELECT count(DISTINCT subject_id) FROM staff_alert
                 WHERE kind = 'PAYMENT_TO_CONFIRM' AND NOT email_limited AND raised_at > ?""", Integer.class,
                Timestamp.from(now.minus(Duration.ofHours(24))));
        boolean limited = emailedToday != null && emailedToday >= limit;

        NotificationSettings.Hold hold = settings.alerts().hold();
        Optional<Instant> remindAt = limited ? Optional.empty()
                : new HoldRemindTime(hold.remindBefore(), hold.quietFrom(), hold.quietUntil(), settings.zone()).of(holdExpiresAt, now);
        Text first = MessageTexts.paymentToConfirm(orderId, method, holdExpiresAt, settings.zone());
        Optional<UUID> alert = raise(new NewAlert(Kind.PAYMENT_TO_CONFIRM, subject, orderId, first, holdExpiresAt, remindAt.orElse(null),
                MessageTexts.paymentToConfirmReminder(orderId, method, holdExpiresAt, settings.zone()),
                holdExpiresAt.plus(hold.closeGrace()), limited), now);
        if (alert.isEmpty()) {
            return;
        }
        if (limited) {
            staffRow(alert.get(), Kind.PAYMENT_TO_CONFIRM, "FIRST", 1, first, null, "NOT_SENT", "DAILY_LIMIT", null, null, now);
            tellThatClaimsAreNoLongerEmailed(limit, now);
            return;
        }
        if (Duration.between(factRecordedAt, now).compareTo(settings.alerts().maxFactAge()) <= 0) {
            tell(alert.get(), Kind.PAYMENT_TO_CONFIRM, "FIRST", 1, first, now, holdExpiresAt, now);
        }
    }

    /** One alert a day (the shop's day) that says payment claims are no longer emailed one by one. It is itself never limited. */
    private void tellThatClaimsAreNoLongerEmailed(int limit, Instant now) {
        LocalDate today = now.atZone(settings.zone()).toLocalDate();
        Text words = MessageTexts.claimEmailLimit(limit);
        raise(new NewAlert(Kind.CLAIM_EMAIL_LIMIT, today.toString(), null, words, null, null, null, now.plus(Duration.ofHours(24)), false), now)
                .ifPresent(id -> {
                    tell(id, Kind.CLAIM_EMAIL_LIMIT, "FIRST", 1, words, now, null, now);
                    log.error("More than {} payment claims in a day: each new one is listed but no longer emailed (alert {})", limit, id);
                });
    }

    // ---- alerts about email itself: listed and logged, never emailed -----------------------------------------------------------------------------

    /** The mail server has been failing for a while. @return whether this call raised it (so it is logged once) */
    public boolean emailIsDown(Instant since, Instant now) {
        return transaction.execute(tx -> raise(new NewAlert(Kind.EMAIL_DOWN, "email", null, MessageTexts.emailDown(since, settings.zone()), null,
                null, null, null, false), now).isPresent());
    }

    /**
     * The mail server refused the last three recipients, each a different one: it is refusing everybody. Listed like "the mail server is
     * failing", and ended the same way by the next accepted message. Unlike that one it also closes by itself after a day: three dead
     * addresses in a row look the same, and in a quiet shop nothing would ever be accepted to say otherwise.
     *
     * @return whether this call raised it (so it is logged once)
     */
    public boolean everybodyIsBeingRefused(Instant now) {
        return transaction.execute(tx -> raise(new NewAlert(Kind.EMAIL_DOWN, "email", null, MessageTexts.emailDown(now, settings.zone()), null,
                null, null, now.plus(Duration.ofHours(24)), false), now).isPresent());
    }

    public void emailWorks(Instant now) {
        resolve(Kind.EMAIL_DOWN, "email", "RECOVERED", now);
    }

    /** Whether "emails are not going out" stands right now. While it does, a refused recipient is not believed (the server refuses everybody). */
    public boolean emailIsListedAsDown() {
        return newest(Kind.EMAIL_DOWN, "email").map(Stored::open).orElse(false);
    }

    /**
     * The alerts of one kind that closed by the clock and whose last note was never written. Found from what is stored, not from what a pass
     * remembers: if the service stops between closing an alert and writing its note, the next pass still writes it.
     */
    public List<Closed> closedByTheClockWithNoLastNote(Kind kind) {
        return jdbc.query("""
                SELECT a.id, a.kind, a.subject_id FROM staff_alert a
                 WHERE a.kind = ? AND a.state = 'RESOLVED' AND a.resolved_how = 'BY_CLOCK'
                   AND NOT EXISTS (SELECT 1 FROM notification_log m WHERE m.alert_id = a.id AND m.alert_notice = 'CLOSING')
                 ORDER BY a.resolved_at, a.id LIMIT ?""",
                (row, n) -> new Closed(row.getObject(1, UUID.class), Kind.valueOf(row.getString(2)), row.getString(3)), kind.name(), ALERTS_A_PASS);
    }

    /**
     * The last note of an alert that closed by the clock, to every mailbox of its group, all or none. The alert's row is locked and looked
     * at again first: if its real answer arrived since it was found ("paid", "money held"), nothing is "never answered" about it and no
     * note is written.
     */
    public void tellTheLastNote(UUID alertId, Kind kind, Text words, Instant now) {
        each(alertId, () -> {
            boolean stillNeverAnswered = !jdbc.query(
                    "SELECT 1 FROM staff_alert WHERE id = ? AND state = 'RESOLVED' AND resolved_how = 'BY_CLOCK' FOR UPDATE", (row, n) -> 1, alertId)
                    .isEmpty();
            if (stillNeverAnswered) {
                tell(alertId, kind, "CLOSING", 1, words, now, null, now);
            }
        });
    }

    /** The mail server refused one of the alert mailboxes. It stays listed for a day. @return whether this call raised it */
    public boolean alertMailboxRefused(Instant now) {
        return transaction.execute(tx -> raise(new NewAlert(Kind.EMAIL_DOWN, "alert-mailbox", null, MessageTexts.alertMailboxRefused(), null, null,
                null, now.plus(Duration.ofHours(24)), false), now).isPresent());
    }

    // ---- the clock -------------------------------------------------------------------------------------------------------------------------

    /**
     * What the passing of time does to alerts, called at the start of every dispatcher pass: reminders that are due, alerts that close by
     * themselves, and open alerts nobody was ever told about. Each alert is handled in a transaction of its own; one that fails is logged by
     * its id and the others go on.
     */
    public List<Closed> clock(Instant now) {
        remind(now);
        List<Closed> closed = close(now);
        if (setup.canSend()) {
            tellAgain(now);
        }
        return closed;
    }

    /** An alert that closed by the clock: nothing ended it before its time ran out. */
    public record Closed(UUID id, Kind kind, String subjectId) {
    }

    private record Reminder(UUID id, Kind kind, Instant remindAt, Text words, int sent, Instant tellBefore, Instant closesAt) {
    }

    private void remind(Instant now) {
        List<Reminder> due = jdbc.query("""
                SELECT id, kind, remind_at, remind_subject, remind_body, reminders_sent, tell_before, closes_at FROM staff_alert
                 WHERE state = 'OPEN' AND remind_at <= ? ORDER BY id LIMIT ?""",
                (row, n) -> new Reminder(row.getObject("id", UUID.class), Kind.valueOf(row.getString("kind")), row.getTimestamp("remind_at").toInstant(),
                        new Text(row.getString("remind_subject"), row.getString("remind_body")), row.getInt("reminders_sent"),
                        instant(row.getTimestamp("tell_before")), instant(row.getTimestamp("closes_at"))),
                Timestamp.from(now), ALERTS_A_PASS);
        for (Reminder reminder : due) {
            each(reminder.id(), () -> {
                // One statement takes the reminder, so two instances reminding together write one set of rows.
                Instant next = nextReminder(reminder, now);
                int taken = jdbc.update("""
                        UPDATE staff_alert SET reminders_sent = reminders_sent + 1, remind_at = ?
                         WHERE id = ? AND state = 'OPEN' AND remind_at = ?""", timestamp(next), reminder.id(), Timestamp.from(reminder.remindAt()));
                if (taken == 1) {
                    tell(reminder.id(), reminder.kind(), "REMINDER", reminder.sent() + 1, reminder.words(), now, reminder.tellBefore(), now);
                }
            });
        }
    }

    /**
     * When an alert is reminded of again after this reminder. "Money recorded but the sale has not completed" repeats every
     * {@code alerts.settlement.remind-every} until it closes; a payment claim is reminded of once.
     *
     * <p>The next one always lies AHEAD of now, at the same hour of the day as before: after days with the service away, staff get one
     * reminder, not one for every day that was missed, seconds apart.
     */
    private Instant nextReminder(Reminder reminder, Instant now) {
        if (reminder.kind() != Kind.SETTLEMENT_STUCK) {
            return null;
        }
        Duration every = settings.alerts().settlement().remindEvery();
        Instant next = reminder.remindAt().plus(every);
        while (!next.isAfter(now)) {
            next = next.plus(every);
        }
        return reminder.closesAt() == null || next.isBefore(reminder.closesAt()) ? next : null;
    }

    private List<Closed> close(Instant now) {
        List<Closed> closed = jdbc.query("""
                UPDATE staff_alert SET state = 'RESOLVED', resolved_at = ?, resolved_how = 'BY_CLOCK', remind_at = NULL
                 WHERE state = 'OPEN' AND closes_at <= ? RETURNING id, kind, subject_id""",
                (row, n) -> new Closed(row.getObject(1, UUID.class), Kind.valueOf(row.getString(2)), row.getString(3)), Timestamp.from(now),
                Timestamp.from(now));
        if (!closed.isEmpty()) {
            log.info("{} alert(s) closed by themselves: nothing closed them before their time ran out", closed.size());
        }
        return closed;
    }

    private record Untold(UUID id, Kind kind, Text words, Instant tellBefore, int tellings) {
    }

    private void tellAgain(Instant now) {
        List<Untold> untold = jdbc.query("""
                SELECT a.id, a.kind, a.summary, a.detail, a.tell_before,
                       (SELECT COALESCE(max(m.alert_tell_no), 0) FROM notification_log m
                         WHERE m.alert_id = a.id AND m.alert_notice IN ('FIRST', 'AGAIN')) AS tellings
                  FROM staff_alert a
                 WHERE a.state = 'OPEN' AND a.emailed_at IS NULL AND NOT a.email_limited AND a.kind NOT IN ('EMAIL_DOWN', 'BELOW_COST')
                   AND (a.tell_before IS NULL OR a.tell_before > ?)
                   AND EXISTS (SELECT 1 FROM notification_log m WHERE m.alert_id = a.id)
                   AND NOT EXISTS (SELECT 1 FROM notification_log m WHERE m.alert_id = a.id AND m.status = 'RECORDED')
                   AND (SELECT max(m.status_at) FROM notification_log m WHERE m.alert_id = a.id) <= ?
                 ORDER BY a.id LIMIT ?""",
                (row, n) -> new Untold(row.getObject("id", UUID.class), Kind.valueOf(row.getString("kind")),
                        new Text(row.getString("summary"), row.getString("detail")), instant(row.getTimestamp("tell_before")), row.getInt("tellings")),
                Timestamp.from(now), Timestamp.from(now.minus(settings.alerts().retellAfter())), ALERTS_A_PASS);
        for (Untold alert : untold) {
            if (recipients(alert.kind()).isEmpty() || alert.tellings() > settings.alerts().retellMax()) {
                continue;
            }
            each(alert.id(), () -> tell(alert.id(), alert.kind(), "AGAIN", alert.tellings() + 1, alert.words(), now, alert.tellBefore(), now));
        }
    }

    private void each(UUID alertId, Runnable work) {
        try {
            transaction.executeWithoutResult(tx -> work.run());
        } catch (RuntimeException failure) {
            log.error("Alert {} could not be handled in this pass ({}); the others go on", alertId, failure.getClass().getSimpleName());
        }
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}

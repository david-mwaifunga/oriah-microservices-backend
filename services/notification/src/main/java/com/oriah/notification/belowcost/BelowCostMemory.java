package com.oriah.notification.belowcost;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.oriah.notification.alerts.StaffAlerts;
import com.oriah.notification.alerts.StaffAlerts.Kind;
import com.oriah.notification.alerts.StaffAlerts.NewAlert;
import com.oriah.notification.belowcost.CatalogBelowCostClient.Item;
import com.oriah.notification.config.EmailSetup;
import com.oriah.notification.config.NotificationSettings;
import com.oriah.notification.text.MessageTexts;
import com.oriah.notification.text.MessageTexts.Text;

/**
 * What the below-cost check remembers between rounds, which is how it knows that a piece is NEWLY below cost: an open alert per piece
 * ({@code staff_alert}, kind BELOW_COST, about the variant). Catalog keeps no "since when"; it works out where a price stands from the stock
 * held at every read.
 *
 * <p>One GOOD round (the whole list read) is applied in ONE transaction:
 * <ul>
 *   <li>a piece listed with no open alert opens one;</li>
 *   <li>an open alert whose piece is no longer listed counts a round; after {@code below-cost.clear-after-rounds} good rounds in a row without
 *       it, it ends (so a piece that blinks out of one reading is not told twice);</li>
 *   <li>the pieces nobody has been emailed about are told in ONE email per mailbox (a digest), unless a digest naming them is still waiting
 *       to go, or the owner was told about the same piece in the last {@code below-cost.quiet-for}: the quiet time delays a telling, it never
 *       drops one, and a digest that could not be sent is simply written again by a later round;</li>
 *   <li>"the check is not running" ends.</li>
 * </ul>
 * A round that failed never reaches this class, so a failure can never read as "nothing is below cost".
 */
public class BelowCostMemory {

    private static final String PIECE_SUMMARY_ENDS = " is priced below cost";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final StaffAlerts alerts;
    private final EmailSetup setup;
    private final NotificationSettings settings;

    public BelowCostMemory(JdbcTemplate jdbc, PlatformTransactionManager transactions, StaffAlerts alerts, EmailSetup setup,
                           NotificationSettings settings) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactions);
        this.alerts = alerts;
        this.setup = setup;
        this.settings = settings;
    }

    /** @param listed the pieces catalog lists as on sale AND below cost, the whole list of one good round */
    public void succeeded(List<Item> listed, Instant now) {
        transaction.executeWithoutResult(tx -> apply(listed, now));
    }

    private void apply(List<Item> listed, Instant now) {
        // Two instances must never apply two rounds at once.
        jdbc.query("SELECT 1 FROM below_cost_poll WHERE id = 1 FOR UPDATE", row -> { });

        Map<String, UUID> open = new HashMap<>();
        jdbc.query("SELECT subject_id, id FROM staff_alert WHERE kind = 'BELOW_COST' AND state = 'OPEN'",
                row -> { open.put(row.getString(1), row.getObject(2, UUID.class)); });
        List<String> stillListed = new ArrayList<>();
        for (Item piece : listed) {
            String variant = piece.variantId().toString();
            stillListed.add(variant);
            if (!open.containsKey(variant)) {
                alerts.raise(new NewAlert(Kind.BELOW_COST, variant, null, MessageTexts.belowCostPiece(name(piece)), null, null, null, null, false), now);
            }
        }
        for (Map.Entry<String, UUID> alert : open.entrySet()) {
            if (stillListed.contains(alert.getKey())) {
                jdbc.update("UPDATE staff_alert SET absent_rounds = 0 WHERE id = ? AND absent_rounds <> 0", alert.getValue());
            } else {
                jdbc.update("""
                        UPDATE staff_alert
                           SET absent_rounds = absent_rounds + 1,
                               state = CASE WHEN absent_rounds + 1 >= ? THEN 'RESOLVED' ELSE state END,
                               resolved_at = CASE WHEN absent_rounds + 1 >= ? THEN ?::timestamptz ELSE NULL END,
                               resolved_how = CASE WHEN absent_rounds + 1 >= ? THEN 'NO_LONGER_BELOW' ELSE NULL END
                         WHERE id = ?""", settings.belowCost().clearAfterRounds(), settings.belowCost().clearAfterRounds(), Timestamp.from(now),
                        settings.belowCost().clearAfterRounds(), alert.getValue());
            }
        }
        tellTheUntold(now);

        alerts.resolve(Kind.BELOW_COST_CHECK_DOWN, BelowCostPoller.CHECK, "RECOVERED", now);
        jdbc.update("UPDATE below_cost_poll SET last_success_at = ?, down_since = NULL, last_failure = NULL, leased_until = NULL WHERE id = 1",
                Timestamp.from(now));
    }

    private record Untold(UUID alertId, String name) {
    }

    /** One digest for every open piece nobody was emailed about, with the three exceptions in the class comment. */
    private void tellTheUntold(Instant now) {
        List<String> mailboxes = setup.costRecipients();
        if (mailboxes.isEmpty() || !setup.canSend()) {
            // The alerts stand and are listed. They are told by the first good round after a mailbox is given or email is switched on.
            return;
        }
        List<Untold> untold = jdbc.query("""
                SELECT a.id, a.summary FROM staff_alert a
                 WHERE a.kind = 'BELOW_COST' AND a.state = 'OPEN' AND a.emailed_at IS NULL
                   AND NOT EXISTS (SELECT 1 FROM below_cost_digest_piece p JOIN notification_log m ON m.digest_id = p.digest_id
                                    WHERE p.alert_id = a.id AND m.status = 'RECORDED')
                   AND NOT EXISTS (SELECT 1 FROM staff_alert told
                                    WHERE told.kind = 'BELOW_COST' AND told.subject_id = a.subject_id AND told.emailed_at > ?)
                   AND NOT EXISTS (SELECT 1 FROM below_cost_digest_piece p JOIN notification_log m ON m.digest_id = p.digest_id
                                    WHERE p.alert_id = a.id AND m.recorded_at > ?)
                   AND (SELECT count(DISTINCT p.digest_id) FROM below_cost_digest_piece p WHERE p.alert_id = a.id) <= ?
                 ORDER BY a.raised_at, a.summary""",
                (row, n) -> new Untold(row.getObject(1, UUID.class), nameFrom(row.getString(2))),
                Timestamp.from(now.minus(settings.belowCost().quietFor())),
                // A digest that could not be delivered (a refused mailbox, mail down for longer than a digest is worth sending) is written
                // again, but like any other alert: not before alerts.retell-after, and alerts.retell-max times at most. Without this a
                // refused cost mailbox would be written to on every round, for ever.
                Timestamp.from(now.minus(settings.alerts().retellAfter())), settings.alerts().retellMax());
        if (untold.isEmpty()) {
            return;
        }
        Text words = MessageTexts.belowCostDigest(untold.stream().map(Untold::name).toList(), settings.belowCost().maxNamed());
        UUID digest = UUID.randomUUID();
        for (String mailbox : mailboxes) {
            jdbc.update("""
                    INSERT INTO notification_log (id, event_type, channel, subject, body, recorded_at, recipient_kind, audience, staff_to, status,
                                                  status_at, next_attempt_at, send_before, alert_notice, alert_tell_no, digest_id, created_at)
                    VALUES (?, 'alert.below_cost', 'email', ?, ?, ?, 'STAFF', 'COST', ?, 'RECORDED', ?, ?, ?, 'DIGEST', 1, ?, ?)""",
                    UUID.randomUUID(), words.subject(), words.body(), Timestamp.from(now), mailbox, Timestamp.from(now), Timestamp.from(now),
                    Timestamp.from(now.plus(settings.belowCost().digestWindow())), digest, Timestamp.from(now));
        }
        for (Untold piece : untold) {
            jdbc.update("INSERT INTO below_cost_digest_piece (digest_id, alert_id) VALUES (?, ?)", digest, piece.alertId());
        }
    }

    /** A piece as a person would name it: catalog's variant title (which begins with the product's title) and the SKU. */
    private static String name(Item piece) {
        return piece.variantTitle() + " (SKU " + piece.sku() + ")";
    }

    /** The same name, read back from a stored alert's one line. */
    private static String nameFrom(String summary) {
        return summary.endsWith(PIECE_SUMMARY_ENDS) ? summary.substring(0, summary.length() - PIECE_SUMMARY_ENDS.length()) : summary;
    }
}

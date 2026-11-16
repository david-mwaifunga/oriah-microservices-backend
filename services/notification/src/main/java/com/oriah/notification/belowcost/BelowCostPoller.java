package com.oriah.notification.belowcost;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import com.oriah.notification.alerts.StaffAlerts;
import com.oriah.notification.alerts.StaffAlerts.Kind;
import com.oriah.notification.alerts.StaffAlerts.NewAlert;
import com.oriah.notification.belowcost.CatalogBelowCostClient.BelowCostReply;
import com.oriah.notification.belowcost.CatalogBelowCostClient.Item;
import com.oriah.notification.config.NotificationSettings;
import com.oriah.notification.text.MessageTexts;
import com.oriah.notification.text.MessageTexts.Text;

import feign.FeignException;
import feign.RetryableException;

/**
 * The below-cost check. Every {@code below-cost.delay} it reads catalog's whole list of pieces on sale for less than they cost and hands a
 * GOOD reading to {@link BelowCostMemory}, which knows what is new.
 *
 * <p><b>Anything short of a whole, consistent list is "unknown".</b> A refused token, catalog (or the stock and promotion services it asks)
 * being away, a timeout, a list whose size changed between two pages: none of these opens or clears anything. They are counted, and when no
 * round has been good for {@code below-cost.down-after} the people who would be told about prices are told instead that the check is not
 * running, once.
 *
 * <p>That health is judged AFTER the round, from a "down since" kept in the database: a host that slept for hours and then makes a good round
 * raises nothing, and a restart in the middle of an outage keeps counting from when the outage began.
 *
 * <p>Only one instance runs a round at a time (a lease on the one {@code below_cost_poll} row). The instance that did not get the lease does
 * nothing at all: it neither polls nor judges.
 */
public class BelowCostPoller {

    private static final Logger log = LoggerFactory.getLogger(BelowCostPoller.class);
    /** The fixed subject of the alert "the below-cost check is not running". */
    static final String CHECK = "below-cost-check";
    private static final String BELOW = "BELOW";
    private static final String ON_SALE = "ACTIVE";
    private static final int PAGE_SIZE = 100;
    private static final Duration LEASE = Duration.ofMinutes(2);

    private enum Round {
        GOOD, FAILED, NOT_OWNER
    }

    private final CatalogBelowCostClient catalog;
    private final BelowCostMemory memory;
    private final StaffAlerts alerts;
    private final JdbcTemplate jdbc;
    private final NotificationSettings settings;
    private final String clientSecret;
    private final Runnable dropTheCachedToken;

    /**
     * @param clientSecret       notification's own client secret; when it is empty nothing is asked of catalog and the check counts as down
     * @param dropTheCachedToken called when catalog refuses the token, so the next round signs in afresh (after a Keycloak reset, say)
     */
    public BelowCostPoller(CatalogBelowCostClient catalog, BelowCostMemory memory, StaffAlerts alerts, JdbcTemplate jdbc,
                           NotificationSettings settings, String clientSecret, Runnable dropTheCachedToken) {
        this.catalog = catalog;
        this.memory = memory;
        this.alerts = alerts;
        this.jdbc = jdbc;
        this.settings = settings;
        this.clientSecret = clientSecret;
        this.dropTheCachedToken = dropTheCachedToken;
    }

    /** One tick of the schedule. Never throws. */
    public void tick(Instant now) {
        try {
            if (round(now) == Round.FAILED) {
                judge(now);
            }
        } catch (RuntimeException failure) {
            log.error("The below-cost check failed in an unexpected way ({})", failure.getClass().getSimpleName());
        }
    }

    private Round round(Instant now) {
        // The lease first, whatever follows: an instance that does not hold it judges nothing and writes nothing (not even "no secret").
        // Its end is the holder's mark: only the instance whose lease still stands may give it back or write a failure down.
        Timestamp lease = Timestamp.from(now.plus(LEASE).truncatedTo(ChronoUnit.MICROS));
        int mine = jdbc.update("""
                UPDATE below_cost_poll SET leased_until = ?, last_attempt_at = ?
                 WHERE id = 1 AND (leased_until IS NULL OR leased_until < ?)""", lease, Timestamp.from(now), Timestamp.from(now));
        if (mine == 0) {
            return Round.NOT_OWNER;
        }
        if (clientSecret == null || clientSecret.isBlank()) {
            return failed("NO_SECRET", now, lease);
        }
        List<Item> onSaleAndBelow = new ArrayList<>();
        try {
            long total = -1;
            long read = 0;
            Set<UUID> seen = new HashSet<>();
            for (int page = 0; total < 0 || (long) page * PAGE_SIZE < total; page++) {
                BelowCostReply reply = catalog.belowCost(BELOW, page, PAGE_SIZE);
                if (reply == null || reply.items() == null || (total >= 0 && reply.totalItems() != total)) {
                    // Each page is a fresh evaluation by catalog. A list that changed between two pages is not one list.
                    return failed("INCONSISTENT", now, lease);
                }
                total = reply.totalItems();
                read += reply.items().size();
                for (Item piece : reply.items()) {
                    if (piece.variantId() != null && !seen.add(piece.variantId())) {
                        // Catalog sorts the list afresh for every page. A piece read twice means the order changed between two pages, and
                        // then another piece was on no page at all, although the total is the same: this is not one list either.
                        return failed("INCONSISTENT", now, lease);
                    }
                    if (piece.variantId() != null && BELOW.equals(piece.assessment()) && ON_SALE.equals(piece.productStatus())) {
                        onSaleAndBelow.add(piece);
                    }
                }
            }
            if (read != total) {
                return failed("INCONSISTENT", now, lease);
            }
        } catch (RetryableException away) {
            // Feign's own word for "this may pass later": no answer at all (a timeout, a refused connection: no status), or an answer that
            // said when to come back (catalog's 503 with Retry-After, when stock or promotions could not be asked).
            return failed(away.status() > 0 ? "UNAVAILABLE" : "TIMEOUT", now, lease);
        } catch (FeignException refused) {
            if (refused.status() == 401 || refused.status() == 403) {
                // After a Keycloak reset the cached token is signed by keys that no longer exist: sign in afresh next time.
                dropTheCachedToken.run();
                log.error("Catalog refused notification's own identity ({}): the below-cost check cannot read the list", refused.status());
                return failed("REFUSED", now, lease);
            }
            log.warn("The below-cost list could not be read (status {})", refused.status());
            return failed("UNAVAILABLE", now, lease);
        } catch (TokenUnavailable noToken) {
            log.warn("The below-cost check could not sign in to Keycloak ({})", noToken.getMessage());
            return failed("TOKEN", now, lease);
        } catch (RuntimeException other) {
            log.warn("The below-cost list could not be read ({})", other.getClass().getSimpleName());
            return failed("OTHER", now, lease);
        }
        try {
            memory.succeeded(onSaleAndBelow, now);
        } catch (RuntimeException notStored) {
            // The kind of failure only: its message could quote a row.
            log.error("A good reading of the below-cost list could not be stored ({})", notStored.getClass().getSimpleName());
            return failed("OTHER", now, lease);
        }
        return Round.GOOD;
    }

    /**
     * Writes a failed round down and gives the lease back, but only while this round's lease still stands. A round that outlived its lease
     * (catalog hung, another instance took over) is nobody's to judge any more: it changes nothing and counts as not the holder's.
     */
    private Round failed(String why, Instant now, Timestamp lease) {
        int stillMine = jdbc.update("""
                UPDATE below_cost_poll SET down_since = COALESCE(down_since, ?), last_failure = ?, leased_until = NULL
                 WHERE id = 1 AND leased_until = ?""", Timestamp.from(now), why, lease);
        return stillMine == 1 ? Round.FAILED : Round.NOT_OWNER;
    }

    /** After a failed round: has the check been down long enough to say so? Told once; it stays open until a good round. */
    private void judge(Instant now) {
        Timestamp downSince = jdbc.queryForObject("SELECT down_since FROM below_cost_poll WHERE id = 1", Timestamp.class);
        if (downSince == null || Duration.between(downSince.toInstant(), now).compareTo(settings.belowCost().downAfter()) <= 0) {
            return;
        }
        Text words = MessageTexts.belowCostCheckDown(downSince.toInstant(), settings.zone());
        alerts.raiseAndTell(new NewAlert(Kind.BELOW_COST_CHECK_DOWN, CHECK, null, words, null, null, null, null, false), now);
    }

    /** Keycloak could not give notification a token. */
    public static final class TokenUnavailable extends RuntimeException {
        public TokenUnavailable(String why) {
            super(why);
        }
    }
}

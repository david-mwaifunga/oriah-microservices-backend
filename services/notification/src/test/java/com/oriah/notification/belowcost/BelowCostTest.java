package com.oriah.notification.belowcost;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;

import com.oriah.notification.MutableClock;
import com.oriah.notification.ServiceTest;
import com.oriah.notification.TestData;
import com.oriah.notification.alerts.ConfirmationWatch;
import com.oriah.notification.alerts.StaffAlerts;
import com.oriah.notification.belowcost.CatalogBelowCostClient.BelowCostReply;
import com.oriah.notification.belowcost.CatalogBelowCostClient.Item;
import com.oriah.notification.config.EmailSetup;
import com.oriah.notification.config.EmailSetup.Mode;
import com.oriah.notification.config.NotificationSettings;
import com.oriah.notification.contact.ContactBook;
import com.oriah.notification.delivery.DispatchHealth;
import com.oriah.notification.delivery.Email;
import com.oriah.notification.delivery.MessageDispatcher;
import com.oriah.notification.delivery.MessageStore;
import com.oriah.notification.delivery.RecordingTransport;
import com.oriah.notification.delivery.SendResult;
import com.oriah.notification.orders.OrderRefs;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The below-cost check: every few minutes notification asks catalog which pieces are on sale for less than they cost, and tells the people
 * allowed to see costs when a piece NEWLY is. Catalog stores no "since when" (where a price stands is worked out from the stock held at every
 * read), so "newly" is remembered here: an open alert per piece.
 *
 * <p>A round that could not be read in full is "unknown", never "nothing is below cost": it opens nothing and clears nothing. No cost figure and
 * no price is ever read, stored or sent: an email names the piece, and the figures stay on the Prices screen behind the cost capability.
 */
@ServiceTest
class BelowCostTest {

    private static final Instant START = MutableClock.START;
    private static final List<String> COST = List.of("alerts-cost@oriah.local");

    @Autowired
    private MessageStore store;
    @Autowired
    private ContactBook contacts;
    @Autowired
    private OrderRefs orderRefs;
    @Autowired
    private DispatchHealth health;
    @Autowired
    private ConfirmationWatch watch;
    @Autowired
    private NotificationSettings settings;
    @Autowired
    private PlatformTransactionManager transactions;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private MutableClock clock;
    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private final RecordingTransport transport = new RecordingTransport();
    /** What catalog answers, a page at a time; a test may make it throw instead. */
    private final List<Item> below = new ArrayList<>();
    private Supplier<RuntimeException> catalogFails;
    private int catalogCalls;

    private final Item ring = piece("Aurelia Solitaire Ring - Ring Size M", "RING-M");
    private final Item necklace = piece("Lume Moonstone Necklace", "NECK-1");

    @BeforeEach
    void clean() {
        TestData.clean(jdbc, clock);
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------------------------------

    private static Item piece(String title, String sku) {
        return new Item(UUID.randomUUID(), UUID.randomUUID(), title, sku, "ACTIVE", "BELOW");
    }

    private EmailSetup setup(List<String> cost) {
        return new EmailSetup(Mode.CATCHER, null, List.of("alerts-money@oriah.local"), cost, null, false);
    }

    private StaffAlerts alerts(List<String> cost) {
        return new StaffAlerts(jdbc, transactions, setup(cost), settings);
    }

    private BelowCostPoller poller(List<String> cost, String secret) {
        CatalogBelowCostClient catalog = (status, page, size) -> {
            catalogCalls++;
            if (catalogFails != null) {
                throw catalogFails.get();
            }
            int from = Math.min(page * size, below.size());
            return new BelowCostReply(below.subList(from, Math.min(from + size, below.size())), Map.of("BELOW", (long) below.size()), page, size, below.size());
        };
        return new BelowCostPoller(catalog, new BelowCostMemory(jdbc, transactions, alerts(cost), setup(cost), settings), alerts(cost), jdbc,
                settings, secret, () -> { });
    }

    private BelowCostPoller poller() {
        return poller(COST, "a-secret");
    }

    private void dispatch() {
        new MessageDispatcher(store, contacts, orderRefs, health, alerts(COST), watch, transport, setup(COST), settings, clock).run();
    }

    private List<Map<String, Object>> alertsOf(String kind) {
        return jdbc.queryForList("SELECT * FROM staff_alert WHERE kind = ? ORDER BY raised_at, summary", kind);
    }

    private List<Map<String, Object>> digests() {
        return jdbc.queryForList("SELECT * FROM notification_log WHERE alert_notice = 'DIGEST' ORDER BY recorded_at");
    }

    // ---- newly below cost ------------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("the first good round opens an alert for every piece below cost and tells them all in ONE email")
    void theFirstRoundTellsEverythingInOneMessage() {
        below.addAll(List.of(ring, necklace));

        poller().tick(clock.instant());

        assertThat(alertsOf("BELOW_COST")).hasSize(2).allSatisfy(alert -> assertThat(alert).containsEntry("state", "OPEN").containsEntry("audience", "COST"));
        Map<String, Object> ringAlert = jdbc.queryForMap("SELECT * FROM staff_alert WHERE subject_id = ?", ring.variantId().toString());
        assertThat(ringAlert.get("summary")).isEqualTo("Aurelia Solitaire Ring - Ring Size M (SKU RING-M) is priced below cost");
        assertThat(ringAlert.get("detail")).isEqualTo("Aurelia Solitaire Ring - Ring Size M (SKU RING-M) is on sale for less than it cost you. Open "
                + "Prices, Below cost, to see what it cost and to change its price. If the price is like this on purpose, there is nothing to do.");
        assertThat(digests()).singleElement().satisfies(row -> {
            assertThat(row).containsEntry("staff_to", "alerts-cost@oriah.local").containsEntry("event_type", "alert.below_cost")
                    .containsEntry("status", "RECORDED").containsEntry("audience", "COST");
            assertThat(row.get("alert_id")).isNull();
            assertThat(row.get("subject")).isEqualTo("2 pieces are now priced below cost");
            assertThat(row.get("body")).isEqualTo("These pieces are now on sale for less than they cost you:\n"
                    + "- Aurelia Solitaire Ring - Ring Size M (SKU RING-M)\n- Lume Moonstone Necklace (SKU NECK-1)\n"
                    + "Open Prices, Below cost, to see what each one cost and to change its price. If a price is like this on purpose, there is "
                    + "nothing to do.");
            assertThat(((java.sql.Timestamp) row.get("send_before")).toInstant()).isEqualTo(START.plus(Duration.ofHours(1)));
        });

        dispatch();
        assertThat(transport.handedOver()).extracting(Email::to).containsExactly("alerts-cost@oriah.local");
        assertThat(alertsOf("BELOW_COST")).as("a digest a mail server took stamps every piece it named")
                .allSatisfy(alert -> assertThat(alert.get("emailed_at")).isNotNull());
    }

    @Test
    @DisplayName("a piece already told is not told again; a new piece is, alone and in the singular")
    void aPieceAlreadyEmailedIsNotToldAgain() {
        below.add(ring);
        poller().tick(clock.instant());
        dispatch();
        clock.advance(Duration.ofMinutes(5));
        poller().tick(clock.instant());
        assertThat(digests()).hasSize(1);

        below.add(necklace);
        clock.advance(Duration.ofMinutes(5));
        poller().tick(clock.instant());

        assertThat(digests()).hasSize(2);
        assertThat(digests().get(1).get("subject")).isEqualTo("1 piece is now priced below cost");
        assertThat((String) digests().get(1).get("body")).startsWith("This piece is now on sale for less than it cost you:\n- Lume Moonstone Necklace (SKU NECK-1)\n")
                .doesNotContain("Aurelia");
    }

    @Test
    @DisplayName("a piece must be gone for two good rounds before its alert clears; one that blinks out for a round is not told again")
    void aPieceMustBeGoneTwoGoodRoundsBeforeItIsCleared() {
        below.add(ring);
        poller().tick(clock.instant());
        dispatch();

        below.clear();
        poller().tick(clock.instant());
        assertThat(alertsOf("BELOW_COST").getFirst()).containsEntry("state", "OPEN").containsEntry("absent_rounds", 1);
        below.add(ring);
        poller().tick(clock.instant());
        assertThat(alertsOf("BELOW_COST")).hasSize(1).first().satisfies(alert -> assertThat(alert).containsEntry("absent_rounds", 0));
        assertThat(digests()).as("it blinked: nobody is told twice").hasSize(1);

        below.clear();
        poller().tick(clock.instant());
        poller().tick(clock.instant());
        assertThat(alertsOf("BELOW_COST").getFirst()).containsEntry("state", "RESOLVED").containsEntry("resolved_how", "NO_LONGER_BELOW");
    }

    @Test
    @DisplayName("a piece that falls below cost again within a day is emailed when the day is up, if it is still below; the quiet time delays, it never drops")
    void aPieceBackWithinADayIsEmailedWhenTheQuietTimeEnds() {
        below.add(ring);
        poller().tick(clock.instant());
        dispatch();
        below.clear();
        poller().tick(clock.instant());
        poller().tick(clock.instant());

        clock.advance(Duration.ofHours(2));
        below.add(ring);
        poller().tick(clock.instant());
        assertThat(alertsOf("BELOW_COST")).as("a second fall is a new fact").hasSize(2);
        assertThat(digests()).as("but the owner was told about this piece two hours ago").hasSize(1);

        clock.set(START.plus(Duration.ofHours(24)).plusSeconds(1));
        poller().tick(clock.instant());
        assertThat(digests()).hasSize(2);
    }

    @Test
    @DisplayName("a digest that could not be sent is written again by a later round, and no second digest is written while one is still waiting to go")
    void aDigestThatWasNotSentIsWrittenAgainByALaterRound() {
        below.add(ring);
        poller().tick(clock.instant());
        clock.advance(Duration.ofMinutes(5));
        poller().tick(clock.instant());
        assertThat(digests()).as("one is waiting to go").hasSize(1);

        // Mail is down for longer than a digest is worth sending: it gives up.
        clock.advance(Duration.ofHours(1));
        dispatch();
        assertThat(digests().getFirst().get("status")).isIn("NOT_SENT", "FAILED");

        poller().tick(clock.instant());
        assertThat(digests()).hasSize(2);
        assertThat((String) digests().get(1).get("body")).contains("Aurelia Solitaire Ring");
    }

    @Test
    @DisplayName("a digest the mail server refused is not written again on every round: an hour apart, and not for ever")
    void aRefusedDigestIsNotWrittenAgainOnEveryRound() {
        below.add(ring);
        poller().tick(clock.instant());
        transport.thenAnswer(SendResult.refused(SendResult.ADDRESS_REFUSED));
        dispatch();
        assertThat(digests()).singleElement().satisfies(row -> assertThat(row).containsEntry("status", "FAILED"));

        for (int round = 1; round <= 11; round++) {
            clock.advance(Duration.ofMinutes(5));
            poller().tick(clock.instant());
        }
        assertThat(digests()).as("55 minutes and eleven good rounds later").hasSize(1);

        clock.advance(Duration.ofMinutes(5));
        poller().tick(clock.instant());
        assertThat(digests()).as("an hour after the one that failed").hasSize(2);

        // The mailbox stays wrong for days: the piece stays listed, and the emails stop.
        for (int hour = 1; hour <= 30; hour++) {
            transport.thenAnswer(SendResult.refused(SendResult.ADDRESS_REFUSED));
            dispatch();
            clock.advance(Duration.ofHours(1));
            poller().tick(clock.instant());
        }
        assertThat(digests()).as("the first digest and twelve more, then no more").hasSize(13);
        assertThat(alertsOf("BELOW_COST")).singleElement().satisfies(alert -> assertThat(alert).containsEntry("state", "OPEN"));
    }

    @Test
    @DisplayName("pieces that opened before anybody was configured to be told are told once somebody is; a digest whose pieces all cleared is not sent")
    void piecesOpenBeforeAnAddressWasConfiguredAreToldOnceItIs() {
        below.add(ring);
        poller(List.of(), "a-secret").tick(clock.instant());
        assertThat(alertsOf("BELOW_COST")).hasSize(1);
        assertThat(digests()).isEmpty();

        clock.advance(Duration.ofMinutes(5));
        poller().tick(clock.instant());
        assertThat(digests()).hasSize(1);

        below.clear();
        poller().tick(clock.instant());
        poller().tick(clock.instant());
        dispatch();
        assertThat(digests().getFirst()).containsEntry("status", "NOT_SENT").containsEntry("status_reason", "ALERT_RESOLVED");
        assertThat(transport.handedOver()).isEmpty();
    }

    @Test
    @DisplayName("drafts and archived products are not told; more than twenty pieces are named twenty and counted; a very long title does not break the round")
    void whatIsNamed() {
        below.add(new Item(UUID.randomUUID(), UUID.randomUUID(), "A draft ring", "DRAFT-1", "DRAFT", "BELOW"));
        below.add(new Item(UUID.randomUUID(), UUID.randomUUID(), "An archived ring", "ARCH-1", "ARCHIVED", "BELOW"));
        below.add(new Item(UUID.randomUUID(), UUID.randomUUID(), "A ring that is fine", "OK-1", "ACTIVE", "OK"));
        below.add(piece("T".repeat(200) + " - " + "an option, ".repeat(20), "S".repeat(60)));
        for (int i = 1; i <= 24; i++) {
            below.add(piece("Piece " + String.format("%02d", i), "P-" + i));
        }

        poller().tick(clock.instant());

        assertThat(alertsOf("BELOW_COST")).hasSize(25);
        assertThat(digests()).singleElement().satisfies(row -> {
            assertThat(row.get("subject")).isEqualTo("25 pieces are now priced below cost");
            String body = (String) row.get("body");
            assertThat(body.lines().filter(line -> line.startsWith("- "))).hasSize(20);
            assertThat(body).contains("\nand 5 more\n").doesNotContain("draft").doesNotContain("archived").doesNotContain("that is fine");
        });
    }

    @Test
    @DisplayName("no cost figure and no price is read: the reply's own type has no place for one")
    void noCostFigureOrPriceIsRead() {
        assertThat(java.util.Arrays.stream(Item.class.getRecordComponents()).map(component -> component.getName()))
                .containsExactlyInAnyOrder("variantId", "productId", "variantTitle", "sku", "productStatus", "assessment");
    }

    // ---- a round that fails, and the check itself being down -----------------------------------------------------------------------------------

    @Test
    @DisplayName("a round that fails is 'unknown', never 'nothing is below cost': it opens nothing and clears nothing")
    void aFailedRoundChangesNothing() {
        below.add(ring);
        poller().tick(clock.instant());
        below.clear();
        catalogFails = () -> new IllegalStateException("catalog answered 503");

        for (int i = 0; i < 3; i++) {
            clock.advance(Duration.ofMinutes(5));
            poller().tick(clock.instant());
        }

        assertThat(alertsOf("BELOW_COST")).singleElement().satisfies(alert -> assertThat(alert).containsEntry("state", "OPEN").containsEntry("absent_rounds", 0));
        assertThat(jdbc.queryForMap("SELECT down_since, last_failure FROM below_cost_poll"))
                .containsEntry("down_since", java.sql.Timestamp.from(START.plus(Duration.ofMinutes(5))));
    }

    @Test
    @DisplayName("two hours without a good round tells the cost mailboxes once that the check is not running; the next good round clears it")
    void twoHoursWithoutAGoodRoundTellsStaffOnce() {
        catalogFails = () -> new IllegalStateException("catalog is away");
        poller().tick(clock.instant());
        clock.advance(Duration.ofMinutes(119));
        poller().tick(clock.instant());
        assertThat(alertsOf("BELOW_COST_CHECK_DOWN")).isEmpty();

        clock.advance(Duration.ofMinutes(2));
        poller().tick(clock.instant());
        // A restart in the middle of the outage keeps counting from the same moment: a new poller, the same stored "down since".
        poller().tick(clock.instant());

        assertThat(alertsOf("BELOW_COST_CHECK_DOWN")).singleElement().satisfies(alert -> {
            assertThat(alert).containsEntry("state", "OPEN").containsEntry("summary", "The below-cost check is not running");
            assertThat(alert.get("detail")).isEqualTo("The system has not been able to compare your prices with your costs since 09:31 on Monday "
                    + "9 November. Until this is fixed you will not be told when a piece falls below cost. You can still open Prices, Below "
                    + "cost, yourself.");
        });
        dispatch();
        assertThat(transport.handedOver()).singleElement().satisfies(email -> {
            assertThat(email.to()).isEqualTo("alerts-cost@oriah.local");
            assertThat(email.body()).endsWith("yourself. Please contact the person who looks after your system.");
        });

        catalogFails = null;
        clock.advance(Duration.ofMinutes(5));
        poller().tick(clock.instant());
        assertThat(alertsOf("BELOW_COST_CHECK_DOWN").getFirst()).containsEntry("state", "RESOLVED").containsEntry("resolved_how", "RECOVERED");
        assertThat(jdbc.queryForMap("SELECT down_since FROM below_cost_poll").get("down_since")).isNull();
    }

    @Test
    @DisplayName("a long pause followed by a good round raises nothing: health is judged after the round, not before it")
    void aLongPauseFollowedByAGoodRoundRaisesNothing() {
        poller().tick(clock.instant());
        clock.advance(Duration.ofHours(3));
        poller().tick(clock.instant());

        assertThat(alertsOf("BELOW_COST_CHECK_DOWN")).isEmpty();
    }

    @Test
    @DisplayName("with no client secret nothing is asked of catalog and the check counts as down; only one poller runs a round at a time, and the other judges nothing")
    void noSecretAndTheLease() {
        poller(COST, "").tick(clock.instant());
        assertThat(catalogCalls).isZero();
        assertThat(jdbc.queryForMap("SELECT last_failure FROM below_cost_poll")).containsEntry("last_failure", "NO_SECRET");

        // Another instance holds the lease: this one neither polls nor judges the check's health.
        jdbc.update("UPDATE below_cost_poll SET leased_until = ?, down_since = ?", java.sql.Timestamp.from(clock.instant().plusSeconds(60)),
                java.sql.Timestamp.from(START.minus(Duration.ofHours(5))));
        poller().tick(clock.instant());
        assertThat(catalogCalls).isZero();
        assertThat(alertsOf("BELOW_COST_CHECK_DOWN")).as("the instance without the lease never raises 'check down'").isEmpty();
    }

    @Test
    @DisplayName("more than a hundred pieces are read to the end, and a list that changed between pages is thrown away")
    void pagesAreReadToTheEnd() {
        for (int i = 1; i <= 250; i++) {
            below.add(piece("Piece " + i, "P-" + i));
        }
        poller().tick(clock.instant());
        assertThat(catalogCalls).isEqualTo(3);
        assertThat(alertsOf("BELOW_COST")).hasSize(250);

        TestData.clean(jdbc, clock);
        catalogCalls = 0;
        CatalogBelowCostClient shifting = (status, page, size) -> new BelowCostReply(below.subList(0, 100), Map.of(), page, size, page == 0 ? 250 : 249);
        new BelowCostPoller(shifting, new BelowCostMemory(jdbc, transactions, alerts(COST), setup(COST), settings), alerts(COST), jdbc, settings,
                "a-secret", () -> { }).tick(clock.instant());
        assertThat(alertsOf("BELOW_COST")).isEmpty();
        assertThat(jdbc.queryForMap("SELECT last_failure FROM below_cost_poll")).containsEntry("last_failure", "INCONSISTENT");
    }

    @Test
    @DisplayName("a list catalog re-sorted between two pages (one piece read twice, another never) is thrown away although its total did not change")
    void aListReorderedBetweenPagesIsThrownAway() {
        for (int i = 1; i <= 200; i++) {
            below.add(piece("Piece " + i, "P-" + i));
        }
        // A piece's shortfall changed after the first page was read: it moved from the second page to the first, so the second page begins
        // with the piece the first page ended with, and the last piece is on no page at all. The total is 200 both times.
        CatalogBelowCostClient reordered = (status, page, size) -> new BelowCostReply(page == 0 ? below.subList(0, 100) : below.subList(99, 199),
                Map.of(), page, size, 200);

        new BelowCostPoller(reordered, new BelowCostMemory(jdbc, transactions, alerts(COST), setup(COST), settings), alerts(COST), jdbc, settings,
                "a-secret", () -> { }).tick(clock.instant());

        assertThat(alertsOf("BELOW_COST")).as("a reading that is not one list raises nothing and clears nothing").isEmpty();
        assertThat(jdbc.queryForMap("SELECT last_failure FROM below_cost_poll")).containsEntry("last_failure", "INCONSISTENT");
    }

    @Test
    @DisplayName("a lease is given back only by the instance that holds it: one with no secret, or whose round outlived its lease, leaves another's alone and judges nothing")
    void aLeaseIsOnlyGivenBackByItsHolder() {
        java.sql.Timestamp theirs = java.sql.Timestamp.from(clock.instant().plusSeconds(60));
        jdbc.update("UPDATE below_cost_poll SET leased_until = ?", theirs);

        poller(COST, "").tick(clock.instant());

        assertThat(jdbc.queryForMap("SELECT leased_until, last_failure, down_since FROM below_cost_poll")).as("no secret, and not the holder")
                .containsEntry("leased_until", theirs).containsEntry("last_failure", null).containsEntry("down_since", null);

        // This instance's round hangs for longer than its lease; another instance takes the lease and is in the middle of its own round.
        jdbc.update("UPDATE below_cost_poll SET leased_until = NULL");
        java.sql.Timestamp takenOver = java.sql.Timestamp.from(clock.instant().plus(Duration.ofMinutes(10)));
        catalogFails = () -> {
            jdbc.update("UPDATE below_cost_poll SET leased_until = ?", takenOver);
            return new IllegalStateException("catalog did not answer");
        };

        poller().tick(clock.instant());

        assertThat(jdbc.queryForMap("SELECT leased_until, last_failure, down_since FROM below_cost_poll")).as("a round that lost its lease")
                .containsEntry("leased_until", takenOver).containsEntry("last_failure", null).containsEntry("down_since", null);
    }

    @Test
    @DisplayName("a refused answer drops the cached token, so the next round signs in afresh")
    void aRefusedTokenIsDropped() {
        int[] dropped = {0};
        CatalogBelowCostClient refuses = (status, page, size) -> {
            throw new feign.FeignException.Unauthorized("401", feign.Request.create(feign.Request.HttpMethod.GET, "http://catalog/x", Map.of(), null,
                    null, null), null, Map.of());
        };
        new BelowCostPoller(refuses, new BelowCostMemory(jdbc, transactions, alerts(COST), setup(COST), settings), alerts(COST), jdbc, settings,
                "a-secret", () -> dropped[0]++).tick(clock.instant());

        assertThat(dropped[0]).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT last_failure FROM below_cost_poll")).containsEntry("last_failure", "REFUSED");
    }

    @Test
    @DisplayName("a digest whose send fails for the server's own reason is retried like any message; the transport's answer never clears a piece")
    void aDigestIsAMessageLikeAnyOther() {
        below.add(ring);
        poller().tick(clock.instant());
        transport.thenAnswer(SendResult.serverTryLater(SendResult.CONNECT));
        dispatch();

        assertThat(digests().getFirst()).containsEntry("status", "RECORDED");
        assertThat(alertsOf("BELOW_COST").getFirst().get("emailed_at")).isNull();
    }
}

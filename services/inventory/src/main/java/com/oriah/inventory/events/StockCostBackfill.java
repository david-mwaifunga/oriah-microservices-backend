package com.oriah.inventory.events;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Announces ({@code stock.cost_recorded}) every row of the stock ledger that was written BEFORE the event existed, once, at start-up.
 *
 * <p>Finance replays this service's whole outbox, and the outbox is never rewritten, so a sale or a write-off from before Phase 10 has no cost event: without this
 * its cost would be missing from its month for good. Each row is announced once ({@code stock_cost_event} marks it, in the same transaction as the event), so a
 * restart, a second run, or the live path announcing the same row can never announce it twice. Each event carries the row's own date and its own record time.
 *
 * <p>Single instance on purpose (a second instance is harmless, not coordinated: the marker's primary key decides). It must run before finance is started for the
 * first time (the rollout order); one that runs later still reaches finance through the live relay, and finance's re-verify heals anything missed. Chunked, so a
 * big ledger is not one transaction. Switch it off with {@code oriah.inventory.stock-cost-backfill.enabled=false}.
 */
@Component
@ConditionalOnProperty(name = "oriah.inventory.stock-cost-backfill.enabled", havingValue = "true", matchIfMissing = true)
public class StockCostBackfill implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StockCostBackfill.class);
    private static final int CHUNK = 500;

    private final JdbcTemplate jdbc;
    private final StockCostEvents costEvents;
    private final TransactionTemplate tx;

    public StockCostBackfill(JdbcTemplate jdbc, StockCostEvents costEvents, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.costEvents = costEvents;
        this.tx = new TransactionTemplate(transactions);
    }

    @Override
    public void run(ApplicationArguments args) {
        int announced = backfill();
        if (announced > 0) {
            log.info("Announced {} stock movement(s) written before stock.cost_recorded existed", announced);
        }
    }

    /** @return how many events were appended */
    public int backfill() {
        int announced = 0;
        while (true) {
            List<Long> ids = jdbc.queryForList("""
                    SELECT m.id FROM stock_movement m
                     WHERE m.role IN ('ORIGINAL', 'RECUT_REVERSAL', 'RECUT_REPLACEMENT') AND m.reason <> 'CONSIGNMENT_RECEIVED'
                       AND NOT EXISTS (SELECT 1 FROM stock_cost_event e WHERE e.movement_id = m.id)
                     ORDER BY m.id LIMIT ?""", Long.class, CHUNK);
            if (ids.isEmpty()) {
                return announced;
            }
            Integer done = tx.execute(status -> costEvents.announce(ids));
            if (done == null || done == 0) {
                log.warn("{} stock movement(s) could not be announced and were left alone (first id {}): not looping on them", ids.size(), ids.get(0));
                return announced;
            }
            announced += done;
        }
    }
}

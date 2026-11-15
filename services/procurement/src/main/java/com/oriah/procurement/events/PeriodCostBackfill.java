package com.oriah.procurement.events;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.oriah.procurement.model.Consignment;
import com.oriah.procurement.model.CostLine;
import com.oriah.procurement.repository.ConsignmentRepository;
import com.oriah.procurement.service.ConsignmentAllocations;

/**
 * Announces the bills for the month ({@code consignment.period_costs}) of every allocation revision that was recorded BEFORE the event existed, once, at start-up.
 *
 * <p>Finance replays this service's whole outbox, and the outbox is never rewritten, so a bill that was keyed before Phase 10 has no event: without this it would
 * be missing from its month for good. Each revision is looked at once ({@code period_cost_event} is its marker, written in the same transaction as the event), so a
 * restart, a second run, or the live path announcing the same revision can never announce it twice. A revision with no bill to announce is marked and passes.
 *
 * <p>Single instance on purpose: it runs in each consignment's own transaction, in receipt order, and the marker's primary key is what makes a second instance
 * harmless rather than what coordinates it. It must run before finance is started for the first time (the rollout order); one that runs later still reaches finance
 * through the live relay, and finance's re-verify heals anything missed. Switch it off with {@code oriah.procurement.period-cost-backfill.enabled=false}.
 */
@Component
@ConditionalOnProperty(name = "oriah.procurement.period-cost-backfill.enabled", havingValue = "true", matchIfMissing = true)
public class PeriodCostBackfill implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PeriodCostBackfill.class);
    static final String ACTOR = "system:procurement";

    private final JdbcTemplate jdbc;
    private final ConsignmentRepository consignments;
    private final ConsignmentAllocations allocations;
    private final ProcurementEvents events;
    private final TransactionTemplate tx;

    public PeriodCostBackfill(JdbcTemplate jdbc, ConsignmentRepository consignments, ConsignmentAllocations allocations, ProcurementEvents events,
                              PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.consignments = consignments;
        this.allocations = allocations;
        this.events = events;
        this.tx = new TransactionTemplate(transactions);
    }

    @Override
    public void run(ApplicationArguments args) {
        int announced = backfill();
        if (announced > 0) {
            log.info("Announced {} bill(s) for the month recorded before consignment.period_costs existed", announced);
        }
    }

    /** @return how many events were appended */
    public int backfill() {
        List<UUID> received = jdbc.queryForList("SELECT id FROM consignment WHERE status = 'RECEIVED' ORDER BY received_at, id", UUID.class);
        int announced = 0;
        for (UUID id : received) {
            Integer done = tx.execute(status -> announce(id));
            announced += done == null ? 0 : done;
        }
        return announced;
    }

    private int announce(UUID id) {
        Consignment consignment = consignments.findById(id).orElse(null);
        if (consignment == null) {
            return 0;
        }
        int announced = 0;
        List<CostLine> previous = List.of();
        int latest = allocations.latestRevision(id);
        for (int revision = 1; revision <= latest; revision++) {
            ConsignmentAllocations.RevisionState state = allocations.stateOf(consignment, revision);
            boolean first = revision == 1;
            if (events.periodCosts(consignment, revision, first ? null : state.createdAt(), first ? null : state.reason(), state.costs(), previous,
                    state.createdAt(), ACTOR)) {
                announced++;
            }
            previous = state.costs();
        }
        return announced;
    }
}

package com.oriah.finance.ledger;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The advisory locks that keep the books exact, all held to the end of the transaction that takes them, and always taken in THIS order:
 *
 * <ol>
 *   <li>the ledger lock, SHARED by every writer and EXCLUSIVE for whoever issues a month: issuing waits for every writer that is mid-flight, so everything with a
 *       recorded time up to the issue is committed, and anything written later is stamped later (an "as of" cut is then reproducible for ever);</li>
 *   <li>the confirmation's lock: two events about one confirmation (it came in, it was voided, it paid a sale) are never derived from a partial view of each other;</li>
 *   <li>the order's lock: one confirmation pays one order, so two confirmations of one order are decided one at a time.</li>
 * </ol>
 */
@Component
public class Locks {

    /** "FINA": the ledger lock's key. */
    static final long LEDGER = 0x46494E41L;

    private final JdbcTemplate jdbc;

    public Locks(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Every writer of the ledger takes this first, shared with every other writer. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void writer() {
        jdbc.query("SELECT pg_advisory_xact_lock_shared(?)", (RowCallbackHandler) rs -> { }, LEDGER);
    }

    /** Issuing a month takes this exclusively: it waits for every writer in flight and holds off every new one until it commits. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void issuer() {
        jdbc.query("SELECT pg_advisory_xact_lock(?)", (RowCallbackHandler) rs -> { }, LEDGER);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void confirmation(UUID confirmationId) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", (RowCallbackHandler) rs -> { }, "conf:" + confirmationId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void order(UUID orderId) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", (RowCallbackHandler) rs -> { }, "order:" + orderId);
    }
}

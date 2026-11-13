package com.oriah.promotion.service;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.money.Ngwee;
import com.oriah.promotion.exception.PromotionProblem;

import lombok.extern.slf4j.Slf4j;

/**
 * Promotions as the client runs them (round 5: "they can be on a piece, a category or even sell wide"). A promotion has a scope (one product, one
 * category with its sub-categories, or the whole shop), dates (Lusaka calendar days, the last day included) and a discount per piece (a Kwacha
 * amount off each piece, or a percentage). It applies by itself; staff never choose a discount.
 *
 * <h2>Never edited, so the past has one answer</h2>
 *
 * A promotion is never changed. A stop is a dated fact, and an edit is a REPLACEMENT: the old promotion stops now and the new one says which it
 * replaces. So for any instant, in the past or now, "which promotions were in force" is worked out from the rows alone: a paper sale keyed after a
 * promotion ended is still priced at its true date, and a promotion made today never reaches back to a sale made earlier today (it counts only from
 * the instant it was made). The database refuses everything else (UPDATE but the one stop, DELETE, TRUNCATE).
 *
 * <p>A promotion may not START in the past: history is not rewritten. Which products a category promotion covers is catalog's to say (it owns
 * the category tree); this service keeps the definitions.
 */
@Service
@Slf4j
public class PromotionService {

    private static final Set<String> SCOPES = Set.of("PRODUCT", "CATEGORY", "SHOP");
    private static final Set<String> DISCOUNTS = Set.of("FIXED", "PERCENTAGE");

    /** A promotion as stored. {@code scopeType} is PRODUCT, CATEGORY or SHOP; {@code discountType} is FIXED (Kwacha off each piece) or PERCENTAGE. */
    public record PromotionView(UUID id, String name, String scopeType, UUID scopeId, String discountType, BigDecimal amount, LocalDate startsOn,
                                LocalDate endsOn, Instant stoppedAt, String stoppedBy, UUID replacesId, Instant createdAt, String createdBy) {
    }

    private static final String SELECT = """
            SELECT id, name, scope_type, scope_id, discount_type, amount, starts_on, ends_on, stopped_at, stopped_by, replaces_id, created_at, created_by
              FROM promotion""";

    private static final String IN_FORCE = " (starts_on <= ? AND ends_on >= ? AND created_at <= ? AND (stopped_at IS NULL OR stopped_at > ?))";

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ZoneId zone;

    public PromotionService(JdbcTemplate jdbc, Clock clock, ZoneId promotionZone) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.zone = promotionZone;
    }

    @Transactional
    public PromotionView create(String name, String scopeType, UUID scopeId, String discountType, BigDecimal amount, LocalDate startsOn, LocalDate endsOn,
                                String actor) {
        return insert(null, name, scopeType, scopeId, discountType, amount, startsOn, endsOn, actor, now());
    }

    /**
     * An edit: the old promotion stops now and the new one starts, saying which it replaces. Both happen in one transaction, at the same instant.
     *
     * @throws PromotionProblem 404 {@code promotion-not-found}; 409 {@code already-stopped}
     */
    @Transactional
    public PromotionView replace(UUID id, String name, String scopeType, UUID scopeId, String discountType, BigDecimal amount, LocalDate startsOn,
                                 LocalDate endsOn, String actor) {
        Instant now = now();
        lockLive(id);
        PromotionView created = insert(id, name, scopeType, scopeId, discountType, amount, startsOn, endsOn, actor, now);
        jdbc.update("UPDATE promotion SET stopped_at = ?, stopped_by = ? WHERE id = ?", Timestamp.from(now), actor, id);
        log.info("Promotion {} replaced by {} ({})", id, created.id(), actor);
        return created;
    }

    /**
     * Ends a promotion from now: it was in force before this instant and is not after it.
     *
     * @throws PromotionProblem 404 {@code promotion-not-found}; 409 {@code already-stopped}
     */
    @Transactional
    public PromotionView stop(UUID id, String actor) {
        lockLive(id);
        jdbc.update("UPDATE promotion SET stopped_at = ?, stopped_by = ? WHERE id = ?", Timestamp.from(now()), actor, id);
        log.info("Promotion {} stopped by {}", id, actor);
        return get(id);
    }

    @Transactional(readOnly = true)
    public PromotionView get(UUID id) {
        return jdbc.query(SELECT + " WHERE id = ?", this::view, id).stream().findFirst()
                .orElseThrow(() -> new PromotionProblem(HttpStatus.NOT_FOUND, "promotion-not-found", "there is no promotion " + id));
    }

    /**
     * @param scopeType   PRODUCT, CATEGORY or SHOP, or null for all
     * @param inForceNow  true: only what is in force now; false: only what is not (scheduled, ended or stopped); null: all
     */
    @Transactional(readOnly = true)
    public List<PromotionView> list(String scopeType, Boolean inForceNow) {
        StringBuilder sql = new StringBuilder(SELECT + " WHERE true");
        List<Object> args = new ArrayList<>();
        if (scopeType != null) {
            sql.append(" AND scope_type = ?");
            args.add(scopeType);
        }
        if (inForceNow != null) {
            Instant now = now();
            sql.append(inForceNow ? " AND" : " AND NOT").append(IN_FORCE);
            args.add(Date(now));
            args.add(Date(now));
            args.add(Timestamp.from(now));
            args.add(Timestamp.from(now));
        }
        sql.append(" ORDER BY created_at DESC, id");
        return jdbc.query(sql.toString(), this::view, args.toArray());
    }

    /** The promotions in force at an instant (null means now), oldest first: the one answer for any past or present moment. */
    @Transactional(readOnly = true)
    public List<PromotionView> inForceAt(Instant at) {
        Instant when = (at == null ? now() : at).truncatedTo(ChronoUnit.MICROS);
        return jdbc.query(SELECT + " WHERE" + IN_FORCE + " ORDER BY created_at, id", this::view, Date(when), Date(when), Timestamp.from(when), Timestamp.from(when));
    }

    // ---------------------------------------------------------------- plumbing

    private PromotionView insert(UUID replaces, String name, String scopeType, UUID scopeId, String discountType, BigDecimal amount, LocalDate startsOn,
                                 LocalDate endsOn, String actor, Instant now) {
        String clean = name == null ? "" : name.trim().replaceAll("\\s+", " ");
        if (clean.isEmpty() || clean.length() > 120) {
            throw new IllegalArgumentException("a promotion needs a name of 1 to 120 characters");
        }
        if (scopeType == null || !SCOPES.contains(scopeType)) {
            throw new IllegalArgumentException("a promotion is for a PRODUCT, a CATEGORY or the whole SHOP, not " + scopeType);
        }
        if ("SHOP".equals(scopeType) != (scopeId == null)) {
            throw new IllegalArgumentException("a product or category promotion names the product or category; a shop-wide one names nothing");
        }
        if (discountType == null || !DISCOUNTS.contains(discountType)) {
            throw new IllegalArgumentException("the discount is FIXED (Kwacha off each piece) or PERCENTAGE, not " + discountType);
        }
        Ngwee.require(amount, "the discount");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("the discount must be above zero");
        }
        if ("PERCENTAGE".equals(discountType) && amount.compareTo(new BigDecimal("100")) > 0) {
            throw new IllegalArgumentException("a percentage is at most 100");
        }
        if (startsOn == null || endsOn == null) {
            throw new IllegalArgumentException("a promotion needs its first and last day");
        }
        if (endsOn.isBefore(startsOn)) {
            throw new IllegalArgumentException("the last day cannot be before the first");
        }
        if (startsOn.isBefore(now.atZone(zone).toLocalDate())) {
            throw new IllegalArgumentException("a promotion cannot start in the past: history is not rewritten (starts " + startsOn + ")");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO promotion (id, name, scope_type, scope_id, discount_type, amount, starts_on, ends_on, replaces_id, created_at, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                id, clean, scopeType, scopeId, discountType, amount, Date(startsOn), Date(endsOn), replaces, Timestamp.from(now), actor);
        log.info("Promotion {} ({} {} {} {}) made by {}", id, clean, scopeType, discountType, amount, actor);
        return get(id);
    }

    /** Locks the promotion and insists it is not already stopped. */
    private void lockLive(UUID id) {
        Instant stopped = jdbc.query("SELECT stopped_at FROM promotion WHERE id = ? FOR UPDATE", rs -> {
            if (!rs.next()) {
                throw new PromotionProblem(HttpStatus.NOT_FOUND, "promotion-not-found", "there is no promotion " + id);
            }
            Timestamp t = rs.getTimestamp(1);
            return t == null ? null : t.toInstant();
        }, id);
        if (stopped != null) {
            throw new PromotionProblem(HttpStatus.CONFLICT, "already-stopped", "promotion " + id + " was already stopped at " + stopped).with("stoppedAt", stopped);
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private java.sql.Date Date(Instant instant) {
        return java.sql.Date.valueOf(instant.atZone(zone).toLocalDate());
    }

    private static java.sql.Date Date(LocalDate date) {
        return java.sql.Date.valueOf(date);
    }

    private PromotionView view(ResultSet rs, int row) throws SQLException {
        Timestamp stopped = rs.getTimestamp("stopped_at");
        return new PromotionView(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("scope_type"), rs.getObject("scope_id", UUID.class),
                rs.getString("discount_type"), rs.getBigDecimal("amount"), rs.getDate("starts_on").toLocalDate(), rs.getDate("ends_on").toLocalDate(),
                stopped == null ? null : stopped.toInstant(), rs.getString("stopped_by"), rs.getObject("replaces_id", UUID.class),
                rs.getTimestamp("created_at").toInstant(), rs.getString("created_by"));
    }
}

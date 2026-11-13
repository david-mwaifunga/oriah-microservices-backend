package com.oriah.catalog.repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.oriah.catalog.pricing.PriceRow;

/**
 * A variant's dated price history. Plain SQL on purpose: the table is append-only (the database refuses an update, a delete and a
 * truncate), has no entity to dirty-check, and is read in the order the rules need. Every method joins the caller's transaction,
 * so a row is written in the same one as the variant's current price and the events that announce it.
 */
@Repository
public class PriceHistoryRepository {

    /** What is written for a new price. */
    public record NewEntry(UUID id, UUID variantId, BigDecimal unitPrice, String currency, Instant effectiveFrom, Instant recordedAt,
                           String setBy, String setByName, String assessment, String reason, String costBasisJson) {
    }

    /** A row as stored. {@code costBasisJson} is null for a migrated row. */
    public record Entry(UUID id, long seq, UUID variantId, BigDecimal unitPrice, String currency, Instant effectiveFrom, Instant recordedAt,
                        String setBy, String setByName, String source, String assessment, String reason, String costBasisJson) {

        public PriceRow asRow() {
            return new PriceRow(seq, effectiveFrom, unitPrice);
        }
    }

    private static final String COLUMNS = "id, seq, variant_id, unit_price, currency, effective_from, recorded_at, set_by, set_by_name, source, assessment, reason, cost_basis::text AS cost_basis";

    private final JdbcTemplate jdbc;

    public PriceHistoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Every row of the variant, newest first: the latest effective date, and for the same instant the last written. */
    public List<Entry> history(UUID variantId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM variant_price WHERE variant_id = ? ORDER BY effective_from DESC, seq DESC",
                this::entry, variantId);
    }

    /** The row in force now, or empty if the variant was never priced. */
    public Optional<Entry> latest(UUID variantId) {
        return jdbc.query("SELECT " + COLUMNS + " FROM variant_price WHERE variant_id = ? ORDER BY effective_from DESC, seq DESC LIMIT 1",
                this::entry, variantId).stream().findFirst();
    }

    /** The latest row of each variant that has one, for the list that compares every price with the cost. */
    public java.util.Map<UUID, Entry> latestOf(java.util.Collection<UUID> variantIds) {
        if (variantIds.isEmpty()) {
            return java.util.Map.of();
        }
        List<Entry> rows = jdbc.query(con -> {
            java.sql.PreparedStatement ps = con.prepareStatement("SELECT DISTINCT ON (variant_id) " + COLUMNS
                    + " FROM variant_price WHERE variant_id = ANY(?) ORDER BY variant_id, effective_from DESC, seq DESC");
            ps.setArray(1, con.createArrayOf("uuid", variantIds.toArray()));
            return ps;
        }, this::entry);
        java.util.Map<UUID, Entry> byVariant = new java.util.LinkedHashMap<>();
        rows.forEach(row -> byVariant.put(row.variantId(), row));
        return byVariant;
    }

    public Entry append(NewEntry entry) {
        return jdbc.queryForObject("""
                INSERT INTO variant_price (id, variant_id, unit_price, currency, effective_from, recorded_at, set_by, set_by_name,
                                           source, assessment, reason, cost_basis)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'SET', ?, ?, ?::jsonb)
                RETURNING """ + " " + COLUMNS,
                this::entry, entry.id(), entry.variantId(), entry.unitPrice(), entry.currency(), java.sql.Timestamp.from(entry.effectiveFrom()),
                java.sql.Timestamp.from(entry.recordedAt()), entry.setBy(), entry.setByName(), entry.assessment(), entry.reason(), entry.costBasisJson());
    }

    private Entry entry(ResultSet rs, int row) throws SQLException {
        return new Entry(rs.getObject("id", UUID.class), rs.getLong("seq"), rs.getObject("variant_id", UUID.class), rs.getBigDecimal("unit_price"),
                rs.getString("currency"), rs.getTimestamp("effective_from").toInstant(), rs.getTimestamp("recorded_at").toInstant(),
                rs.getString("set_by"), rs.getString("set_by_name"), rs.getString("source"), rs.getString("assessment"), rs.getString("reason"),
                rs.getString("cost_basis"));
    }
}

package com.oriah.shipping.service;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.money.Ngwee;
import com.oriah.shipping.exception.ShippingProblem;

import lombok.extern.slf4j.Slf4j;

/**
 * What admin keeps (Phase 7): Zambia's towns, the options a parcel can go by, a price for each town and option, and the free-delivery threshold.
 *
 * <p>A town is switched off, never deleted (orders in another database point at it). A price is a DRAFT until admin PUBLISHES it, and history
 * once published: a new price replaces it and the old one is kept, so what a customer was quoted is what was published, and a later change
 * never touches an order already placed. Every change says the version it was made against, so two admins editing the same thing at the same
 * moment cannot overwrite each other: the second is a 409 naming the current version. The database holds the same rules (a published price
 * refuses UPDATE and DELETE).
 */
@Service
@Slf4j
public class DeliveryAdminService {

    /** Kinds an admin may add. Oriah's own delivery and Yango are fixed (seeded). */
    private static final Set<String> ADDABLE_KINDS = Set.of("COURIER", "BUS_PARCEL");

    public record ProvinceView(UUID id, String code, String name, int towns) {
    }

    public record TownView(UUID id, UUID provinceId, String provinceName, String name, String slug, boolean active, long version) {
    }

    public record OptionView(String code, String name, String kind, boolean active, int position, long version) {
    }

    public record PriceView(UUID id, UUID townId, String townName, String optionCode, BigDecimal amount, String currency, boolean allowsOriahCollects,
                            boolean allowsRecipientPays, Integer etaDaysMin, Integer etaDaysMax, String status, long version, Instant createdAt,
                            String createdBy, Instant publishedAt, String publishedBy, Instant supersededAt) {
    }

    public record SettingsView(BigDecimal freeDeliveryThreshold, String currency, long version, Instant updatedAt, String updatedBy) {
    }

    public record SettingsChange(BigDecimal thresholdBefore, BigDecimal thresholdAfter, Instant changedAt, String changedBy, String reason) {
    }

    private final JdbcTemplate jdbc;

    public DeliveryAdminService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- provinces and towns

    @Transactional(readOnly = true)
    public List<ProvinceView> provinces() {
        return jdbc.query("""
                SELECT p.id, p.code, p.name, (SELECT count(*) FROM town t WHERE t.province_id = p.id) AS towns
                  FROM province p ORDER BY p.position""",
                (rs, n) -> new ProvinceView(rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"), rs.getInt("towns")));
    }

    @Transactional(readOnly = true)
    public List<TownView> towns(UUID provinceId, Boolean active) {
        StringBuilder sql = new StringBuilder(TOWN_SELECT + " WHERE true");
        List<Object> args = new ArrayList<>();
        if (provinceId != null) {
            sql.append(" AND t.province_id = ?");
            args.add(provinceId);
        }
        if (active != null) {
            sql.append(" AND t.active = ?");
            args.add(active);
        }
        sql.append(" ORDER BY p.position, t.name");
        return jdbc.query(sql.toString(), this::mapTown, args.toArray());
    }

    @Transactional
    public TownView addTown(UUID provinceId, String name, String actor) {
        String clean = name == null ? "" : name.trim().replaceAll("\\s+", " ");
        if (clean.isEmpty() || clean.length() > 80) {
            throw new IllegalArgumentException("a town needs a name of 1 to 80 characters");
        }
        String provinceName = jdbc.query("SELECT name FROM province WHERE id = ?", rs -> rs.next() ? rs.getString(1) : null, provinceId);
        if (provinceName == null) {
            throw new ShippingProblem(HttpStatus.NOT_FOUND, "province-not-found", "there is no province " + provinceId);
        }
        if (count("SELECT count(*) FROM town WHERE province_id = ? AND lower(name) = lower(?)", provinceId, clean) > 0) {
            throw townExists(clean, provinceName);
        }
        String slug = slugify(clean);
        if (slug.isEmpty()) {
            throw new IllegalArgumentException("a town's name must have letters or digits in it");
        }
        if (count("SELECT count(*) FROM town WHERE slug = ?", slug) > 0) {
            slug = slug + "-" + slugify(provinceName);
            for (int n = 2; count("SELECT count(*) FROM town WHERE slug = ?", slug) > 0; n++) {
                slug = slugify(clean) + "-" + slugify(provinceName) + "-" + n;
            }
        }
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO town (id, province_id, name, slug, created_by) VALUES (?, ?, ?, ?, ?)", id, provinceId, clean, slug, actor);
        } catch (DuplicateKeyException e) {
            throw townExists(clean, provinceName);
        }
        log.info("Town {} added to {} by {}", clean, provinceName, actor);
        return town(id);
    }

    @Transactional
    public TownView switchTown(UUID townId, boolean active, long version, String actor) {
        int rows = jdbc.update("UPDATE town SET active = ?, version = version + 1, updated_at = now(), updated_by = ? WHERE id = ? AND version = ?",
                active, actor, townId, version);
        if (rows == 0) {
            Long current = jdbc.query("SELECT version FROM town WHERE id = ?", rs -> rs.next() ? rs.getLong(1) : null, townId);
            if (current == null) {
                throw new ShippingProblem(HttpStatus.NOT_FOUND, "town-not-found", "there is no town " + townId);
            }
            throw stale("town", current);
        }
        log.info("Town {} switched {} by {}", townId, active ? "on" : "off", actor);
        return town(townId);
    }

    // ---------------------------------------------------------------- options

    @Transactional(readOnly = true)
    public List<OptionView> options() {
        return jdbc.query("SELECT code, name, kind, active, position, version FROM delivery_option ORDER BY position, code", this::mapOption);
    }

    @Transactional
    public OptionView addOption(String name, String kind, String actor) {
        String clean = name == null ? "" : name.trim().replaceAll("\\s+", " ");
        if (clean.isEmpty() || clean.length() > 80) {
            throw new IllegalArgumentException("an option needs a name of 1 to 80 characters");
        }
        if (kind == null || !ADDABLE_KINDS.contains(kind)) {
            throw new IllegalArgumentException("an option added by admin is a COURIER or a BUS_PARCEL service, not " + kind);
        }
        String code = codeOf(clean);
        if (count("SELECT count(*) FROM delivery_option WHERE code = ? OR lower(name) = lower(?)", code, clean) > 0) {
            throw new ShippingProblem(HttpStatus.CONFLICT, "option-exists", "there is already an option called " + clean).with("code", code);
        }
        Integer position = jdbc.queryForObject("SELECT coalesce(max(position), 0) + 1 FROM delivery_option", Integer.class);
        try {
            jdbc.update("INSERT INTO delivery_option (code, name, kind, position, created_by) VALUES (?, ?, ?, ?, ?)", code, clean, kind, position, actor);
        } catch (DuplicateKeyException e) {
            throw new ShippingProblem(HttpStatus.CONFLICT, "option-exists", "there is already an option called " + clean).with("code", code);
        }
        log.info("Delivery option {} added by {}", code, actor);
        return option(code);
    }

    @Transactional
    public OptionView switchOption(String code, boolean active, long version, String actor) {
        int rows = jdbc.update("UPDATE delivery_option SET active = ?, version = version + 1, updated_at = now(), updated_by = ? WHERE code = ? AND version = ?",
                active, actor, code, version);
        if (rows == 0) {
            Long current = jdbc.query("SELECT version FROM delivery_option WHERE code = ?", rs -> rs.next() ? rs.getLong(1) : null, code);
            if (current == null) {
                throw new ShippingProblem(HttpStatus.NOT_FOUND, "option-not-found", "there is no delivery option " + code);
            }
            throw stale("option", current);
        }
        log.info("Delivery option {} switched {} by {}", code, active ? "on" : "off", actor);
        return option(code);
    }

    // ---------------------------------------------------------------- prices

    @Transactional(readOnly = true)
    public List<PriceView> prices(UUID townId, String optionCode, String status) {
        StringBuilder sql = new StringBuilder(PRICE_SELECT + " WHERE true");
        List<Object> args = new ArrayList<>();
        if (townId != null) {
            sql.append(" AND d.town_id = ?");
            args.add(townId);
        }
        if (optionCode != null) {
            sql.append(" AND d.option_code = ?");
            args.add(optionCode);
        }
        if (status != null) {
            sql.append(" AND d.status = ?");
            args.add(status);
        }
        sql.append(" ORDER BY d.created_at, d.id");
        return jdbc.query(sql.toString(), this::mapPrice, args.toArray());
    }

    @Transactional
    public PriceView draftPrice(UUID townId, String optionCode, BigDecimal amount, boolean allowsOriahCollects, boolean allowsRecipientPays,
                                Integer etaDaysMin, Integer etaDaysMax, String actor) {
        checkPrice(amount, allowsOriahCollects, allowsRecipientPays, etaDaysMin, etaDaysMax);
        if (count("SELECT count(*) FROM town WHERE id = ?", townId) == 0) {
            throw new ShippingProblem(HttpStatus.NOT_FOUND, "town-not-found", "there is no town " + townId);
        }
        if (count("SELECT count(*) FROM delivery_option WHERE code = ?", optionCode) == 0) {
            throw new ShippingProblem(HttpStatus.NOT_FOUND, "option-not-found", "there is no delivery option " + optionCode);
        }
        UUID existing = jdbc.query("SELECT id FROM delivery_price WHERE town_id = ? AND option_code = ? AND status = 'DRAFT'",
                rs -> rs.next() ? rs.getObject(1, UUID.class) : null, townId, optionCode);
        if (existing != null) {
            throw draftExists(existing);
        }
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("""
                    INSERT INTO delivery_price (id, town_id, option_code, amount, allows_oriah_collects, allows_recipient_pays, eta_days_min, eta_days_max,
                                                status, created_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', ?)""",
                    id, townId, optionCode, amount, allowsOriahCollects, allowsRecipientPays, etaDaysMin, etaDaysMax, actor);
        } catch (DuplicateKeyException e) {
            UUID raced = jdbc.query("SELECT id FROM delivery_price WHERE town_id = ? AND option_code = ? AND status = 'DRAFT'",
                    rs -> rs.next() ? rs.getObject(1, UUID.class) : null, townId, optionCode);
            throw draftExists(raced);
        }
        log.info("Price draft {} ({} {} {}) by {}", id, townId, optionCode, amount, actor);
        return price(id);
    }

    @Transactional
    public PriceView editDraft(UUID priceId, BigDecimal amount, boolean allowsOriahCollects, boolean allowsRecipientPays, Integer etaDaysMin,
                               Integer etaDaysMax, long version, String actor) {
        lockDraft(priceId, version);
        checkPrice(amount, allowsOriahCollects, allowsRecipientPays, etaDaysMin, etaDaysMax);
        jdbc.update("""
                UPDATE delivery_price SET amount = ?, allows_oriah_collects = ?, allows_recipient_pays = ?, eta_days_min = ?, eta_days_max = ?, version = version + 1
                 WHERE id = ?""", amount, allowsOriahCollects, allowsRecipientPays, etaDaysMin, etaDaysMax, priceId);
        log.info("Price draft {} edited by {}", priceId, actor);
        return price(priceId);
    }

    @Transactional
    public void discardDraft(UUID priceId, long version) {
        lockDraft(priceId, version);
        jdbc.update("DELETE FROM delivery_price WHERE id = ?", priceId);
        log.info("Price draft {} discarded", priceId);
    }

    /**
     * Makes the draft the live price of its town and option: the one that was live (if any) becomes history in the same transaction.
     */
    @Transactional
    public PriceView publish(UUID priceId, long version, String actor) {
        PriceRow draft = lockDraft(priceId, version);
        jdbc.update("""
                UPDATE delivery_price SET status = 'SUPERSEDED', superseded_at = now()
                 WHERE town_id = ? AND option_code = ? AND status = 'PUBLISHED'""", draft.townId(), draft.optionCode());
        jdbc.update("UPDATE delivery_price SET status = 'PUBLISHED', published_at = now(), published_by = ?, version = version + 1 WHERE id = ?", actor, priceId);
        log.info("Price {} published by {} ({} {})", priceId, actor, draft.townId(), draft.optionCode());
        return price(priceId);
    }

    // ---------------------------------------------------------------- the free-delivery threshold

    @Transactional(readOnly = true)
    public SettingsView settings() {
        return jdbc.queryForObject("SELECT free_delivery_threshold, version, updated_at, updated_by FROM delivery_settings WHERE id = 1",
                (rs, n) -> new SettingsView(rs.getBigDecimal("free_delivery_threshold"), "ZMW", rs.getLong("version"), rs.getTimestamp("updated_at").toInstant(),
                        rs.getString("updated_by")));
    }

    /**
     * Sets (or, with null, switches off) the free-delivery threshold: compared with the goods total AFTER discount. Every change is kept.
     */
    @Transactional
    public SettingsView setThreshold(BigDecimal threshold, long version, String reason, String actor) {
        if (threshold != null) {
            Ngwee.require(threshold, "the free-delivery threshold");
            if (threshold.signum() <= 0) {
                throw new IllegalArgumentException("the free-delivery threshold must be above zero (or off)");
            }
        }
        String why = reason == null || reason.isBlank() ? null : reason.trim();
        if (why != null && why.length() > 200) {
            throw new IllegalArgumentException("the reason is at most 200 characters");
        }
        BigDecimal before = jdbc.queryForObject("SELECT free_delivery_threshold FROM delivery_settings WHERE id = 1 FOR UPDATE", BigDecimal.class);
        int rows = jdbc.update("""
                UPDATE delivery_settings SET free_delivery_threshold = ?, version = version + 1, updated_at = now(), updated_by = ?
                 WHERE id = 1 AND version = ?""", threshold, actor, version);
        if (rows == 0) {
            throw stale("settings", jdbc.queryForObject("SELECT version FROM delivery_settings WHERE id = 1", Long.class));
        }
        jdbc.update("INSERT INTO delivery_settings_change (threshold_before, threshold_after, changed_by, reason) VALUES (?, ?, ?, ?)", before, threshold, actor, why);
        log.info("Free-delivery threshold {} -> {} by {}", before, threshold, actor);
        return settings();
    }

    @Transactional(readOnly = true)
    public List<SettingsChange> settingsHistory() {
        return jdbc.query("SELECT threshold_before, threshold_after, changed_at, changed_by, reason FROM delivery_settings_change ORDER BY id DESC",
                (rs, n) -> new SettingsChange(rs.getBigDecimal("threshold_before"), rs.getBigDecimal("threshold_after"),
                        rs.getTimestamp("changed_at").toInstant(), rs.getString("changed_by"), rs.getString("reason")));
    }

    // ---------------------------------------------------------------- plumbing

    private static final String TOWN_SELECT = """
            SELECT t.id, t.province_id, p.name AS province_name, t.name, t.slug, t.active, t.version
              FROM town t JOIN province p ON p.id = t.province_id""";

    private static final String PRICE_SELECT = """
            SELECT d.id, d.town_id, t.name AS town_name, d.option_code, d.amount, d.currency, d.allows_oriah_collects, d.allows_recipient_pays,
                   d.eta_days_min, d.eta_days_max, d.status, d.version, d.created_at, d.created_by, d.published_at, d.published_by, d.superseded_at
              FROM delivery_price d JOIN town t ON t.id = d.town_id""";

    private record PriceRow(UUID id, UUID townId, String optionCode, String status, long version) {
    }

    /** Locks the price row and insists it is a draft of the version the caller saw. */
    private PriceRow lockDraft(UUID priceId, long version) {
        Optional<PriceRow> row = jdbc.query("SELECT id, town_id, option_code, status, version FROM delivery_price WHERE id = ? FOR UPDATE",
                (rs, n) -> new PriceRow(rs.getObject("id", UUID.class), rs.getObject("town_id", UUID.class), rs.getString("option_code"),
                        rs.getString("status"), rs.getLong("version")), priceId).stream().findFirst();
        PriceRow found = row.orElseThrow(() -> new ShippingProblem(HttpStatus.NOT_FOUND, "price-not-found", "there is no price " + priceId));
        if (!"DRAFT".equals(found.status())) {
            throw new ShippingProblem(HttpStatus.CONFLICT, "price-published", "price " + priceId + " is " + found.status().toLowerCase(Locale.ROOT)
                    + ": a published price is history; draft a new one to replace it").with("status", found.status());
        }
        if (found.version() != version) {
            throw stale("price", found.version());
        }
        return found;
    }

    private static void checkPrice(BigDecimal amount, boolean allowsOriahCollects, boolean allowsRecipientPays, Integer etaDaysMin, Integer etaDaysMax) {
        Ngwee.require(amount, "the delivery price");
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("a delivery price cannot be negative");
        }
        if (!allowsOriahCollects && !allowsRecipientPays) {
            throw new IllegalArgumentException("a price must be offered to at least one payer: Oriah collects it, or the recipient pays the courier");
        }
        if ((etaDaysMin == null) != (etaDaysMax == null)) {
            throw new IllegalArgumentException("give both ends of the delivery time, or neither");
        }
        if (etaDaysMin != null && (etaDaysMin < 0 || etaDaysMax < etaDaysMin)) {
            throw new IllegalArgumentException("the delivery time must run from a day count of at least 0 up to one no smaller");
        }
    }

    private static ShippingProblem stale(String what, long currentVersion) {
        return new ShippingProblem(HttpStatus.CONFLICT, "stale-version", "someone else changed this " + what + ": reload it and try again")
                .with("currentVersion", currentVersion);
    }

    private static ShippingProblem draftExists(UUID priceId) {
        return new ShippingProblem(HttpStatus.CONFLICT, "draft-exists", "this town and option already have a draft price: edit it or discard it first")
                .with("priceId", priceId);
    }

    private static ShippingProblem townExists(String name, String province) {
        return new ShippingProblem(HttpStatus.CONFLICT, "town-exists", name + " is already a town of " + province);
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private TownView town(UUID id) {
        return jdbc.query(TOWN_SELECT + " WHERE t.id = ?", this::mapTown, id).stream().findFirst()
                .orElseThrow(() -> new ShippingProblem(HttpStatus.NOT_FOUND, "town-not-found", "there is no town " + id));
    }

    private OptionView option(String code) {
        return jdbc.query("SELECT code, name, kind, active, position, version FROM delivery_option WHERE code = ?", this::mapOption, code).stream().findFirst()
                .orElseThrow(() -> new ShippingProblem(HttpStatus.NOT_FOUND, "option-not-found", "there is no delivery option " + code));
    }

    private PriceView price(UUID id) {
        return jdbc.query(PRICE_SELECT + " WHERE d.id = ?", this::mapPrice, id).stream().findFirst()
                .orElseThrow(() -> new ShippingProblem(HttpStatus.NOT_FOUND, "price-not-found", "there is no price " + id));
    }

    private TownView mapTown(ResultSet rs, int row) throws SQLException {
        return new TownView(rs.getObject("id", UUID.class), rs.getObject("province_id", UUID.class), rs.getString("province_name"), rs.getString("name"),
                rs.getString("slug"), rs.getBoolean("active"), rs.getLong("version"));
    }

    private OptionView mapOption(ResultSet rs, int row) throws SQLException {
        return new OptionView(rs.getString("code"), rs.getString("name"), rs.getString("kind"), rs.getBoolean("active"), rs.getInt("position"), rs.getLong("version"));
    }

    private PriceView mapPrice(ResultSet rs, int row) throws SQLException {
        return new PriceView(rs.getObject("id", UUID.class), rs.getObject("town_id", UUID.class), rs.getString("town_name"), rs.getString("option_code"),
                rs.getBigDecimal("amount"), rs.getString("currency"), rs.getBoolean("allows_oriah_collects"), rs.getBoolean("allows_recipient_pays"),
                (Integer) rs.getObject("eta_days_min"), (Integer) rs.getObject("eta_days_max"), rs.getString("status"), rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant(), rs.getString("created_by"), instant(rs.getTimestamp("published_at")), rs.getString("published_by"),
                instant(rs.getTimestamp("superseded_at")));
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    /** Lower-case ASCII with hyphens: what a URL or a log line says. Accents are dropped, not turned into noise. */
    static String slugify(String name) {
        String ascii = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return ascii.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
    }

    /** An option's code: its name in upper case with underscores, starting with a letter, at most 40 characters. */
    static String codeOf(String name) {
        String ascii = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        String code = ascii.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (code.isEmpty()) {
            throw new IllegalArgumentException("an option's name must have letters or digits in it");
        }
        if (!Character.isLetter(code.charAt(0))) {
            code = "X_" + code;
        }
        return code.length() > 40 ? code.substring(0, 40).replaceAll("_+$", "") : code;
    }
}

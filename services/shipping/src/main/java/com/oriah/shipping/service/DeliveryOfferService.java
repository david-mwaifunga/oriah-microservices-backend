package com.oriah.shipping.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.money.Ngwee;
import com.oriah.shipping.exception.ShippingProblem;

import lombok.extern.slf4j.Slf4j;

/**
 * What a customer is offered and what it costs, BEFORE paying (CLIENT_DECISIONS Q24: "before we commit, we all know the price"). There is no
 * awaiting-quote state: a town and option are offered only when a price is PUBLISHED for them and both are switched on; anything else is the
 * shop's to price first, and the customer is told so plainly ({@code town-not-offered}), never "outside the delivery area".
 *
 * <p>Self-collect is outside the matrix: always offered, at no charge, needing no town.
 *
 * <p>The amount that goes on the order is worked out HERE and nowhere else (checkout and order never compute it):
 * <ul>
 *   <li>the recipient pays the courier ({@code RECIPIENT_PAYS}), only where the price offers it: the order carries K0 (the fare is still
 *       shown as {@code listAmount});</li>
 *   <li>Oriah collects ({@code ORIAH_COLLECTS}): the published price, waived when the goods total AFTER discount reaches the free-delivery
 *       threshold (off until admin sets it); the waiver says so and keeps what it would have cost.</li>
 * </ul>
 * Each quote names the published price it used, so what a customer was quoted can always be traced; a later price never touches an order.
 */
@Service
@Slf4j
public class DeliveryOfferService {

    public static final String ORIAH_COLLECTS = "ORIAH_COLLECTS";
    public static final String RECIPIENT_PAYS = "RECIPIENT_PAYS";
    /** The customer collects the piece from Oriah, at no charge. The code the shopper's choice is carried by, from preview through confirm to the order. */
    public static final String SELF_COLLECT = "SELF_COLLECT";
    /** Oriah's own driver: its charge is income, and it is never paid to the driver on arrival (CLIENT_DECISIONS Q27: no driver cash float). */
    public static final String OWN_DELIVERY = "OWN_DELIVERY";
    private static final String CURRENCY = "ZMW";

    /** Self-collect: always offered, at no charge, outside the town matrix. */
    public record SelfCollect(String code, String name, BigDecimal amount) {
    }

    public record OptionOffer(String code, String name, String kind, BigDecimal amount, boolean allowsOriahCollects, boolean allowsRecipientPays,
                              Integer etaDaysMin, Integer etaDaysMax) {
    }

    public record TownOffer(UUID id, String name, List<OptionOffer> options) {
    }

    public record ProvinceOffer(UUID id, String name, List<TownOffer> towns) {
    }

    public record Offers(String currency, SelfCollect selfCollect, BigDecimal freeDeliveryThreshold, List<ProvinceOffer> provinces) {
    }

    /** A town Oriah can deliver to (switched on), with no price: staff agreeing a delivery by phone choose from these (Phase 9). */
    public record PlaceTown(UUID id, String name) {
    }

    public record PlaceProvince(UUID id, String name, List<PlaceTown> towns) {
    }

    /** A way a parcel can go (switched on): own delivery, Yango, a courier, a bus company's parcel service. Self-collect is not one. */
    public record PlaceOption(String code, String name, String kind) {
    }

    public record Places(List<PlaceProvince> provinces, List<PlaceOption> options) {
    }

    /**
     * The price the customer is quoted for one town and option: {@code amount} is what goes on the order. {@code listAmount} is the published
     * price before the free-delivery threshold; {@code priceId} says which published price it came from (null for self-collect).
     */
    public record Quote(UUID townId, String townName, String optionCode, String optionName, String payer, BigDecimal amount, String currency,
                        BigDecimal listAmount, boolean freeByThreshold, Integer etaDaysMin, Integer etaDaysMax, UUID priceId) {
    }

    private final JdbcTemplate jdbc;

    public DeliveryOfferService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Where Oriah can deliver and by what, WITHOUT any price (Phase 9): every town and option that is switched on, whether or not a price is published for
     * it. The website offers only a published price (a customer is never quoted anything else); a delivery agreed by phone may be for a town not priced yet,
     * and staff must be able to pick it. A province with no active town is left out.
     */
    @Transactional(readOnly = true)
    public Places places() {
        Map<UUID, PlaceProvince> provinces = new LinkedHashMap<>();
        jdbc.query("""
                SELECT p.id AS province_id, p.name AS province_name, t.id AS town_id, t.name AS town_name
                  FROM town t JOIN province p ON p.id = t.province_id
                 WHERE t.active
                 ORDER BY p.position, t.name""",
                (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                    String provinceName = rs.getString("province_name");
                    PlaceProvince province = provinces.computeIfAbsent(rs.getObject("province_id", UUID.class),
                            id -> new PlaceProvince(id, provinceName, new ArrayList<>()));
                    province.towns().add(new PlaceTown(rs.getObject("town_id", UUID.class), rs.getString("town_name")));
                });
        List<PlaceOption> options = jdbc.query("SELECT code, name, kind FROM delivery_option WHERE active ORDER BY position, code",
                (rs, n) -> new PlaceOption(rs.getString("code"), rs.getString("name"), rs.getString("kind")));
        return new Places(new ArrayList<>(provinces.values()), options);
    }

    /** Every town with at least one offered option, grouped by province; a province with nothing offered is left out. */
    @Transactional(readOnly = true)
    public Offers offers() {
        record Row(UUID provinceId, String provinceName, UUID townId, String townName, OptionOffer option) {
        }
        List<Row> rows = jdbc.query("""
                SELECT p.id AS province_id, p.name AS province_name, t.id AS town_id, t.name AS town_name,
                       o.code, o.name AS option_name, o.kind, d.amount, d.allows_oriah_collects, d.allows_recipient_pays, d.eta_days_min, d.eta_days_max
                  FROM delivery_price d
                  JOIN town t ON t.id = d.town_id AND t.active
                  JOIN province p ON p.id = t.province_id
                  JOIN delivery_option o ON o.code = d.option_code AND o.active
                 WHERE d.status = 'PUBLISHED'
                 ORDER BY p.position, t.name, o.position, o.code""",
                (rs, n) -> new Row(rs.getObject("province_id", UUID.class), rs.getString("province_name"), rs.getObject("town_id", UUID.class),
                        rs.getString("town_name"),
                        new OptionOffer(rs.getString("code"), rs.getString("option_name"), rs.getString("kind"), rs.getBigDecimal("amount"),
                                rs.getBoolean("allows_oriah_collects"), recipientMayPay(rs.getString("code"), rs.getBoolean("allows_recipient_pays")),
                                (Integer) rs.getObject("eta_days_min"), (Integer) rs.getObject("eta_days_max"))));
        Map<UUID, ProvinceOffer> provinces = new LinkedHashMap<>();
        Map<UUID, TownOffer> towns = new LinkedHashMap<>();
        for (Row r : rows) {
            if (!r.option().allowsOriahCollects() && !r.option().allowsRecipientPays()) {
                continue;   // a price nobody may pay (an old own-delivery price that only the recipient could) is not offered
            }
            ProvinceOffer province = provinces.computeIfAbsent(r.provinceId(), id -> new ProvinceOffer(id, r.provinceName(), new ArrayList<>()));
            TownOffer town = towns.get(r.townId());
            if (town == null) {
                town = new TownOffer(r.townId(), r.townName(), new ArrayList<>());
                towns.put(r.townId(), town);
                province.towns().add(town);
            }
            town.options().add(r.option());
        }
        return new Offers(CURRENCY, selfCollect(), threshold(), new ArrayList<>(provinces.values()));
    }

    /**
     * @param townId                the town the parcel goes to; not needed (and ignored) for self-collect
     * @param payer                 {@code ORIAH_COLLECTS} or {@code RECIPIENT_PAYS}; null takes the one the price offers (Oriah collecting if both)
     * @param goodsTotalAfterDiscount what the free-delivery threshold is judged on
     * @throws ShippingProblem 404 {@code town-not-found}; 409 {@code town-not-offered}, {@code payer-not-offered}
     * @throws IllegalArgumentException no option, a payer that is not one, a negative or fractional goods total, or a currency but Kwacha
     */
    @Transactional(readOnly = true)
    public Quote quote(UUID townId, String optionCode, String payer, BigDecimal goodsTotalAfterDiscount, String currency) {
        if (currency != null && !currency.isBlank() && !CURRENCY.equalsIgnoreCase(currency.trim())) {
            throw new IllegalArgumentException("delivery is priced in " + CURRENCY + "; cannot quote in " + currency.trim());
        }
        if (optionCode == null || optionCode.isBlank()) {
            throw new IllegalArgumentException("a delivery option is required");
        }
        if (payer != null && !ORIAH_COLLECTS.equals(payer) && !RECIPIENT_PAYS.equals(payer)) {
            throw new IllegalArgumentException("the payer is " + ORIAH_COLLECTS + " or " + RECIPIENT_PAYS + ", not " + payer);
        }
        Ngwee.require(goodsTotalAfterDiscount, "the goods total");
        if (goodsTotalAfterDiscount.signum() < 0) {
            throw new IllegalArgumentException("the goods total cannot be negative");
        }
        String option = optionCode.trim();
        if (SELF_COLLECT.equals(option)) {
            return new Quote(null, null, SELF_COLLECT, "Self-collect", null, zero(), CURRENCY, zero(), false, null, null, null);
        }
        if (townId == null) {
            throw new IllegalArgumentException("a town is required to deliver");
        }
        Map<String, Object> town = jdbc.query("SELECT name, active FROM town WHERE id = ?",
                rs -> rs.next() ? Map.<String, Object>of("name", rs.getString("name"), "active", rs.getBoolean("active")) : null, townId);
        if (town == null) {
            throw new ShippingProblem(HttpStatus.NOT_FOUND, "town-not-found", "there is no town " + townId);
        }
        Map<String, Object> price = town.get("active").equals(Boolean.TRUE) ? jdbc.query("""
                SELECT d.id, d.amount, d.allows_oriah_collects, d.allows_recipient_pays, d.eta_days_min, d.eta_days_max, o.name AS option_name
                  FROM delivery_price d JOIN delivery_option o ON o.code = d.option_code AND o.active
                 WHERE d.town_id = ? AND d.option_code = ? AND d.status = 'PUBLISHED'""",
                rs -> {
                    if (!rs.next()) {
                        return null;
                    }
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getObject("id", UUID.class));
                    m.put("amount", rs.getBigDecimal("amount"));
                    m.put("oriah", rs.getBoolean("allows_oriah_collects"));
                    m.put("recipient", rs.getBoolean("allows_recipient_pays"));
                    m.put("etaMin", rs.getObject("eta_days_min"));
                    m.put("etaMax", rs.getObject("eta_days_max"));
                    m.put("optionName", rs.getString("option_name"));
                    return m;
                }, townId, option) : null;
        if (price == null) {
            throw new ShippingProblem(HttpStatus.CONFLICT, "town-not-offered",
                    "we do not yet deliver to " + town.get("name") + " by this option: ask the shop, who will agree a price and publish it")
                    .with("townId", townId).with("optionCode", option);
        }
        boolean oriahOffered = (Boolean) price.get("oriah");
        boolean recipientOffered = recipientMayPay(option, (Boolean) price.get("recipient"));
        String chosen = payer != null ? payer : (oriahOffered ? ORIAH_COLLECTS : RECIPIENT_PAYS);
        if ((chosen.equals(ORIAH_COLLECTS) && !oriahOffered) || (chosen.equals(RECIPIENT_PAYS) && !recipientOffered)) {
            List<String> offered = new ArrayList<>();
            if (oriahOffered) {
                offered.add(ORIAH_COLLECTS);
            }
            if (recipientOffered) {
                offered.add(RECIPIENT_PAYS);
            }
            throw new ShippingProblem(HttpStatus.CONFLICT, "payer-not-offered", "this delivery is not offered with " + chosen + ": it is offered with " + offered)
                    .with("offered", offered);
        }
        BigDecimal list = (BigDecimal) price.get("amount");
        BigDecimal amount = list;
        boolean free = false;
        if (chosen.equals(RECIPIENT_PAYS)) {
            amount = zero();
        } else {
            BigDecimal threshold = threshold();
            if (threshold != null && goodsTotalAfterDiscount.compareTo(threshold) >= 0) {
                amount = zero();
                free = list.signum() > 0;
            }
        }
        return new Quote(townId, (String) town.get("name"), option, (String) price.get("optionName"), chosen, amount, CURRENCY, list, free,
                (Integer) price.get("etaMin"), (Integer) price.get("etaMax"), (UUID) price.get("id"));
    }

    /**
     * Whether the recipient may pay the courier for this option, given what its published price says: never for Oriah's own delivery, whatever an
     * older price row says (a price made before the rule existed is history, and cannot be changed).
     */
    static boolean recipientMayPay(String optionCode, boolean priceAllowsIt) {
        return priceAllowsIt && !OWN_DELIVERY.equals(optionCode);
    }

    private BigDecimal threshold() {
        return jdbc.queryForObject("SELECT free_delivery_threshold FROM delivery_settings WHERE id = 1", BigDecimal.class);
    }

    private static SelfCollect selfCollect() {
        return new SelfCollect(SELF_COLLECT, "Self-collect", zero());
    }

    private static BigDecimal zero() {
        return new BigDecimal("0.00");
    }
}

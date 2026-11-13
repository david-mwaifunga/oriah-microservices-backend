package com.oriah.shipping.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.oriah.shipping.TestcontainersConfiguration;
import com.oriah.shipping.exception.ShippingProblem;
import com.oriah.shipping.service.DeliveryAdminService.OptionView;
import com.oriah.shipping.service.DeliveryAdminService.PriceView;
import com.oriah.shipping.service.DeliveryAdminService.TownView;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What admin keeps (Phase 7): towns that are switched off and never deleted, options that can be added and switched, and a price for a town
 * and option that is a DRAFT until it is PUBLISHED and is history after (a new price replaces it and the old one stays). Two admins editing
 * the same thing at the same moment cannot overwrite each other: every change says the version it was made against, and a stale copy is a 409
 * that names the current version. A price is never seeded.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class DeliveryAdminServiceTest {

    private static final String ADMIN = "user:kc-maya";

    @Autowired
    private DeliveryAdminService admin;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private UUID province(String name) {
        return admin.provinces().stream().filter(p -> p.name().equals(name)).findFirst().orElseThrow().id();
    }

    /** A town of its own, so a test never meets another test's prices (a published price cannot be removed). */
    private TownView freshTown() {
        return admin.addTown(province("Copperbelt"), "Town " + UUID.randomUUID().toString().substring(0, 8), ADMIN);
    }

    private static ShippingProblem problem(Throwable e) {
        assertThat(e).isInstanceOf(ShippingProblem.class);
        return (ShippingProblem) e;
    }

    // ---------------------------------------------------------------- provinces and towns

    @Test
    @DisplayName("the ten provinces are there, each saying how many towns it has")
    void provinces() {
        var provinces = admin.provinces();

        assertThat(provinces).hasSize(10);
        assertThat(provinces).extracting(DeliveryAdminService.ProvinceView::name).contains("Lusaka", "Copperbelt", "North-Western");
        assertThat(provinces.stream().filter(p -> p.name().equals("Lusaka")).findFirst().orElseThrow().towns()).isGreaterThanOrEqualTo(4);
    }

    @Test
    @DisplayName("a town can be added to a province and switched off and on, but never deleted: a switch is a version-checked change")
    void townsAreSwitchedNeverDeleted() {
        TownView added = admin.addTown(province("Southern"), "Zimba", ADMIN + "x");

        assertThat(added.active()).isTrue();
        assertThat(added.version()).isZero();
        assertThat(added.slug()).isEqualTo("zimba");
        assertThat(added.provinceName()).isEqualTo("Southern");

        TownView off = admin.switchTown(added.id(), false, 0, ADMIN);
        assertThat(off.active()).isFalse();
        assertThat(off.version()).isEqualTo(1);
        assertThat(admin.towns(province("Southern"), false)).extracting(TownView::name).contains("Zimba");
        assertThat(admin.towns(province("Southern"), true)).extracting(TownView::name).doesNotContain("Zimba");

        assertThatThrownBy(() -> admin.switchTown(added.id(), true, 0, ADMIN)).satisfies(e -> {
            assertThat(problem(e).status().value()).isEqualTo(409);
            assertThat(problem(e).code()).isEqualTo("stale-version");
            assertThat(problem(e).properties()).containsEntry("currentVersion", 1L);
        });
        assertThat(admin.switchTown(added.id(), true, 1, ADMIN).active()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM town WHERE id = ?", Integer.class, added.id())).as("never deleted").isEqualTo(1);
    }

    @Test
    @DisplayName("a town's name is its own within its province; the same name in another province gets its own slug")
    void townNamesAreUnique() {
        admin.addTown(province("Southern"), "Batoka", ADMIN);

        assertThatThrownBy(() -> admin.addTown(province("Southern"), "  Batoka ", ADMIN)).satisfies(e -> {
            assertThat(problem(e).status().value()).isEqualTo(409);
            assertThat(problem(e).code()).isEqualTo("town-exists");
        });
        TownView elsewhere = admin.addTown(province("Western"), "Batoka", ADMIN);
        assertThat(elsewhere.slug()).isEqualTo("batoka-western");
        assertThatThrownBy(() -> admin.addTown(UUID.randomUUID(), "Nowhere", ADMIN)).satisfies(e -> assertThat(problem(e).code()).isEqualTo("province-not-found"));
        assertThatThrownBy(() -> admin.addTown(province("Western"), "   ", ADMIN)).isInstanceOf(IllegalArgumentException.class);
    }

    // ---------------------------------------------------------------- options

    @Test
    @DisplayName("an option can be added (a courier, a bus company's parcel service) and switched off, never deleted; its code is made from its name")
    void options() {
        String name = "Journey Bus Parcels " + UUID.randomUUID().toString().substring(0, 4);

        OptionView added = admin.addOption(name, "BUS_PARCEL", ADMIN);

        assertThat(added.code()).startsWith("JOURNEY_BUS_PARCELS_").matches("[A-Z][A-Z0-9_]*");
        assertThat(added.kind()).isEqualTo("BUS_PARCEL");
        assertThat(added.active()).isTrue();
        assertThatThrownBy(() -> admin.addOption(name, "BUS_PARCEL", ADMIN)).satisfies(e -> assertThat(problem(e).code()).isEqualTo("option-exists"));
        assertThatThrownBy(() -> admin.addOption("A Rocket", "ROCKET", ADMIN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> admin.addOption("Own Delivery Two", "OWN_DELIVERY", ADMIN)).as("only couriers and bus services are added").isInstanceOf(IllegalArgumentException.class);

        OptionView off = admin.switchOption(added.code(), false, added.version(), ADMIN);
        assertThat(off.active()).isFalse();
        assertThatThrownBy(() -> admin.switchOption(added.code(), true, 0, ADMIN)).satisfies(e -> assertThat(problem(e).code()).isEqualTo("stale-version"));
        assertThat(admin.options()).extracting(OptionView::code).contains("OWN_DELIVERY", "YANGO", "PLATINUM", "EMS", added.code());
    }

    // ---------------------------------------------------------------- prices

    @Test
    @DisplayName("a price starts as a draft, which one admin can edit under a version and discard; a second draft for the same pair is refused")
    void aPriceIsADraft() {
        TownView town = freshTown();

        PriceView draft = admin.draftPrice(town.id(), "YANGO", new BigDecimal("80.00"), true, false, 1, 2, ADMIN);

        assertThat(draft.status()).isEqualTo("DRAFT");
        assertThat(draft.amount()).isEqualByComparingTo("80.00");
        assertThat(draft.currency()).isEqualTo("ZMW");
        assertThat(draft.createdBy()).isEqualTo(ADMIN);
        assertThat(draft.publishedAt()).isNull();
        assertThatThrownBy(() -> admin.draftPrice(town.id(), "YANGO", new BigDecimal("90.00"), true, false, null, null, ADMIN))
                .satisfies(e -> {
                    assertThat(problem(e).code()).isEqualTo("draft-exists");
                    assertThat(problem(e).properties()).containsEntry("priceId", draft.id());
                });

        PriceView edited = admin.editDraft(draft.id(), new BigDecimal("85.00"), true, true, 2, 3, draft.version(), ADMIN);
        assertThat(edited.amount()).isEqualByComparingTo("85.00");
        assertThat(edited.allowsRecipientPays()).isTrue();
        assertThat(edited.version()).isEqualTo(draft.version() + 1);
        assertThatThrownBy(() -> admin.editDraft(draft.id(), new BigDecimal("1.00"), true, false, null, null, draft.version(), ADMIN))
                .satisfies(e -> assertThat(problem(e).code()).isEqualTo("stale-version"));

        admin.discardDraft(draft.id(), edited.version());
        assertThat(admin.prices(town.id(), "YANGO", null)).isEmpty();
        admin.draftPrice(town.id(), "YANGO", new BigDecimal("70.00"), true, false, null, null, ADMIN);
    }

    @Test
    @DisplayName("publishing makes a price live and records who and when; a newer price replaces it and the old one is kept as history")
    void publishingAndSuperseding() {
        TownView town = freshTown();
        PriceView first = admin.draftPrice(town.id(), "EMS", new BigDecimal("40.00"), true, false, null, null, ADMIN);

        PriceView live = admin.publish(first.id(), first.version(), "user:kc-bea");
        assertThat(live.status()).isEqualTo("PUBLISHED");
        assertThat(live.publishedBy()).isEqualTo("user:kc-bea");
        assertThat(live.publishedAt()).isNotNull();

        PriceView second = admin.draftPrice(town.id(), "EMS", new BigDecimal("55.00"), true, false, null, null, ADMIN);
        PriceView newer = admin.publish(second.id(), second.version(), ADMIN);

        assertThat(newer.status()).isEqualTo("PUBLISHED");
        List<PriceView> history = admin.prices(town.id(), "EMS", null);
        assertThat(history).extracting(PriceView::status, p -> p.amount().toPlainString()).containsExactlyInAnyOrder(
                org.assertj.core.groups.Tuple.tuple("SUPERSEDED", "40.00"), org.assertj.core.groups.Tuple.tuple("PUBLISHED", "55.00"));
        assertThat(history.stream().filter(p -> p.status().equals("SUPERSEDED")).findFirst().orElseThrow().supersededAt()).isNotNull();
        assertThat(admin.prices(town.id(), "EMS", "PUBLISHED")).hasSize(1);
    }

    @Test
    @DisplayName("a published price can no longer be edited or discarded, and a stale publish is refused")
    void aPublishedPriceIsHistory() {
        TownView town = freshTown();
        PriceView draft = admin.draftPrice(town.id(), "PLATINUM", new BigDecimal("70.00"), true, false, null, null, ADMIN);
        assertThatThrownBy(() -> admin.publish(draft.id(), draft.version() + 5, ADMIN)).satisfies(e -> assertThat(problem(e).code()).isEqualTo("stale-version"));
        PriceView live = admin.publish(draft.id(), draft.version(), ADMIN);

        assertThatThrownBy(() -> admin.editDraft(live.id(), new BigDecimal("1.00"), true, false, null, null, live.version(), ADMIN))
                .satisfies(e -> assertThat(problem(e).code()).isEqualTo("price-published"));
        assertThatThrownBy(() -> admin.discardDraft(live.id(), live.version())).satisfies(e -> assertThat(problem(e).code()).isEqualTo("price-published"));
        assertThatThrownBy(() -> admin.publish(live.id(), live.version(), ADMIN)).satisfies(e -> assertThat(problem(e).code()).isEqualTo("price-published"));
    }

    @Test
    @DisplayName("a price must be a whole ngwee amount of at least nothing, offered to at least one payer, with ETAs that go together; the town and option must exist")
    void aPriceIsChecked() {
        TownView town = freshTown();

        assertThatThrownBy(() -> admin.draftPrice(town.id(), "YANGO", new BigDecimal("12.345"), true, false, null, null, ADMIN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> admin.draftPrice(town.id(), "YANGO", new BigDecimal("-1.00"), true, false, null, null, ADMIN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> admin.draftPrice(town.id(), "YANGO", new BigDecimal("10.00"), false, false, null, null, ADMIN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> admin.draftPrice(town.id(), "YANGO", new BigDecimal("10.00"), true, false, 3, null, ADMIN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> admin.draftPrice(town.id(), "YANGO", new BigDecimal("10.00"), true, false, 5, 2, ADMIN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> admin.draftPrice(UUID.randomUUID(), "YANGO", new BigDecimal("10.00"), true, false, null, null, ADMIN))
                .satisfies(e -> assertThat(problem(e).code()).isEqualTo("town-not-found"));
        assertThatThrownBy(() -> admin.draftPrice(town.id(), "TELEPORT", new BigDecimal("10.00"), true, false, null, null, ADMIN))
                .satisfies(e -> assertThat(problem(e).code()).isEqualTo("option-not-found"));
        assertThat(admin.prices(town.id(), null, null)).as("nothing was kept").isEmpty();
        admin.draftPrice(town.id(), "YANGO", new BigDecimal("0.00"), true, false, null, null, ADMIN);   // a free delivery to a town is allowed
    }

    // ---------------------------------------------------------------- the free-delivery threshold

    @Test
    @DisplayName("the free-delivery threshold is off until set; every change is kept with who and why; a stale change is refused")
    void threshold() {
        var start = admin.settings();
        long v = start.version();
        BigDecimal was = start.freeDeliveryThreshold();

        var on = admin.setThreshold(new BigDecimal("1500.00"), v, "trial for the Independence Day week", ADMIN);
        assertThat(on.freeDeliveryThreshold()).isEqualByComparingTo("1500.00");
        assertThat(on.version()).isEqualTo(v + 1);
        assertThat(on.updatedBy()).isEqualTo(ADMIN);
        assertThatThrownBy(() -> admin.setThreshold(new BigDecimal("2000.00"), v, "late", ADMIN)).satisfies(e -> assertThat(problem(e).code()).isEqualTo("stale-version"));

        var off = admin.setThreshold(null, on.version(), "the trial is over", ADMIN);
        assertThat(off.freeDeliveryThreshold()).isNull();

        var history = admin.settingsHistory();
        assertThat(history.get(0).thresholdBefore()).as("newest first: the switch off, from K1,500.00").isEqualByComparingTo("1500.00");
        assertThat(history).extracting(DeliveryAdminService.SettingsChange::reason).contains("trial for the Independence Day week", "the trial is over");
        assertThat(history.get(0).thresholdAfter()).isNull();
        assertThat(history.get(1).thresholdAfter()).isEqualByComparingTo("1500.00");
        assertThat(history.get(1).changedBy()).isEqualTo(ADMIN);
        assertThatThrownBy(() -> admin.setThreshold(BigDecimal.ZERO, off.version(), "zero", ADMIN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> admin.setThreshold(new BigDecimal("10.005"), off.version(), "fraction", ADMIN)).isInstanceOf(IllegalArgumentException.class);
        assertThat(was == null || was.signum() > 0).isTrue();
    }
}

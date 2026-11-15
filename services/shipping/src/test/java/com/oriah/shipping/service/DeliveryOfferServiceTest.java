package com.oriah.shipping.service;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.oriah.shipping.TestcontainersConfiguration;
import com.oriah.shipping.exception.ShippingProblem;
import com.oriah.shipping.service.DeliveryAdminService.PriceView;
import com.oriah.shipping.service.DeliveryAdminService.TownView;
import com.oriah.shipping.service.DeliveryOfferService.Offers;
import com.oriah.shipping.service.DeliveryOfferService.Quote;
import com.oriah.shipping.service.DeliveryOfferService.TownOffer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a customer is offered and quoted BEFORE paying (Q24: "before we commit, we all know the price"). Only a town that is switched on, with an
 * option that is switched on and a price that is PUBLISHED, is offered: a draft is not, and a town not yet priced is the shop's to price (a
 * distinct answer, never "outside the delivery area"). Self-collect is always offered at no charge. The recipient paying the courier means the
 * order carries K0; the free-delivery threshold is judged on the goods total AFTER discount and waives only a fee Oriah would collect.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class DeliveryOfferServiceTest {

    private static final String ADMIN = "user:kc-maya";
    private static final BigDecimal GOODS = new BigDecimal("1000.00");

    @Autowired
    private DeliveryAdminService admin;

    @Autowired
    private DeliveryOfferService offers;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @AfterEach
    void thresholdOff() {
        var s = admin.settings();
        if (s.freeDeliveryThreshold() != null) {
            admin.setThreshold(null, s.version(), "test over", ADMIN);
        }
    }

    private UUID province(String name) {
        return admin.provinces().stream().filter(p -> p.name().equals(name)).findFirst().orElseThrow().id();
    }

    private TownView freshTown() {
        return admin.addTown(province("Copperbelt"), "Offer Town " + UUID.randomUUID().toString().substring(0, 8), ADMIN);
    }

    private PriceView publish(TownView town, String option, String amount, boolean oriah, boolean recipient) {
        PriceView draft = admin.draftPrice(town.id(), option, new BigDecimal(amount), oriah, recipient, 1, 3, ADMIN);
        return admin.publish(draft.id(), draft.version(), ADMIN);
    }

    private static ShippingProblem problem(Throwable e) {
        assertThat(e).isInstanceOf(ShippingProblem.class);
        return (ShippingProblem) e;
    }

    private TownOffer offeredTown(Offers all, TownView town) {
        return all.provinces().stream().flatMap(p -> p.towns().stream()).filter(t -> t.id().equals(town.id())).findFirst().orElse(null);
    }

    // ---------------------------------------------------------------- what is offered

    @Test
    @DisplayName("only a published price, on a town and an option that are switched on, is offered: a draft, a switched-off town and a switched-off option are not")
    void offersOnlyWhatIsPublishedAndOn() {
        TownView priced = freshTown();
        publish(priced, "YANGO", "80.00", true, true);
        admin.draftPrice(priced.id(), "EMS", new BigDecimal("40.00"), true, false, null, null, ADMIN);           // a draft: not offered
        TownView off = freshTown();
        publish(off, "PLATINUM", "70.00", true, false);
        admin.switchTown(off.id(), false, off.version(), ADMIN);
        TownView optionOff = freshTown();
        var bus = admin.addOption("Offer Bus " + UUID.randomUUID().toString().substring(0, 6), "BUS_PARCEL", ADMIN);
        PriceView busPrice = admin.draftPrice(optionOff.id(), bus.code(), new BigDecimal("60.00"), true, false, null, null, ADMIN);
        admin.publish(busPrice.id(), busPrice.version(), ADMIN);
        admin.switchOption(bus.code(), false, bus.version(), ADMIN);

        Offers all = offers.offers();

        TownOffer offered = offeredTown(all, priced);
        assertThat(offered).isNotNull();
        assertThat(offered.options()).extracting(DeliveryOfferService.OptionOffer::code).containsExactly("YANGO");
        var yango = offered.options().get(0);
        assertThat(yango.amount()).isEqualByComparingTo("80.00");
        assertThat(yango.allowsOriahCollects()).isTrue();
        assertThat(yango.allowsRecipientPays()).isTrue();
        assertThat(yango.etaDaysMin()).isEqualTo(1);
        assertThat(yango.etaDaysMax()).isEqualTo(3);
        assertThat(offeredTown(all, off)).as("a switched-off town is not offered").isNull();
        assertThat(offeredTown(all, optionOff)).as("a town whose only option is switched off is not offered").isNull();
        assertThat(all.currency()).isEqualTo("ZMW");
    }

    @Test
    @DisplayName("self-collect is always offered, at no charge, outside the town matrix; the free-delivery threshold is shown when it is on")
    void selfCollectAndThreshold() {
        Offers before = offers.offers();
        assertThat(before.selfCollect().code()).isEqualTo("SELF_COLLECT");
        assertThat(before.selfCollect().amount()).isEqualByComparingTo("0.00");
        assertThat(before.freeDeliveryThreshold()).isNull();

        admin.setThreshold(new BigDecimal("2500.00"), admin.settings().version(), "trial", ADMIN);

        assertThat(offers.offers().freeDeliveryThreshold()).isEqualByComparingTo("2500.00");
    }

    @Test
    @DisplayName("a province none of whose towns is offered is left out")
    void aProvinceWithNothingOfferedIsLeftOut() {
        publish(freshTown(), "EMS", "40.00", true, false);

        var names = offers.offers().provinces().stream().map(DeliveryOfferService.ProvinceOffer::name).toList();

        assertThat(names).contains("Copperbelt").doesNotContain("Luapula", "Muchinga");
    }

    // ---------------------------------------------------------------- the quote

    @Test
    @DisplayName("Oriah collects: the quote is the published price, says which price it came from, and is not free below the threshold")
    void quoteOriahCollects() {
        TownView town = freshTown();
        PriceView price = publish(town, "YANGO", "80.00", true, true);

        Quote q = offers.quote(town.id(), "YANGO", null, GOODS, "ZMW");

        assertThat(q.amount()).isEqualByComparingTo("80.00");
        assertThat(q.listAmount()).isEqualByComparingTo("80.00");
        assertThat(q.payer()).as("Oriah collecting is the default when it is offered").isEqualTo("ORIAH_COLLECTS");
        assertThat(q.freeByThreshold()).isFalse();
        assertThat(q.priceId()).isEqualTo(price.id());
        assertThat(q.townName()).isEqualTo(town.name());
        assertThat(q.optionName()).isEqualTo("Yango");
        assertThat(q.currency()).isEqualTo("ZMW");
        assertThat(q.etaDaysMin()).isEqualTo(1);
    }

    @Test
    @DisplayName("the recipient pays the courier: the order carries K0, whatever the published price, and the fare is still shown")
    void quoteRecipientPays() {
        TownView town = freshTown();
        publish(town, "YANGO", "80.00", true, true);

        Quote q = offers.quote(town.id(), "YANGO", "RECIPIENT_PAYS", GOODS, "ZMW");

        assertThat(q.payer()).isEqualTo("RECIPIENT_PAYS");
        assertThat(q.amount()).as("nothing for Oriah to collect").isEqualByComparingTo("0.00");
        assertThat(q.listAmount()).as("the fare the courier will ask for").isEqualByComparingTo("80.00");
        assertThat(q.freeByThreshold()).isFalse();
    }

    @Test
    @DisplayName("a payer the price does not offer is refused by name, and says which are offered; when only one is offered it is the default")
    void payerNotOffered() {
        TownView town = freshTown();
        publish(town, "EMS", "40.00", false, true);   // the recipient pays the courier, and only that

        assertThatThrownBy(() -> offers.quote(town.id(), "EMS", "ORIAH_COLLECTS", GOODS, "ZMW")).satisfies(e -> {
            assertThat(problem(e).status().value()).isEqualTo(409);
            assertThat(problem(e).code()).isEqualTo("payer-not-offered");
            assertThat(problem(e).properties().get("offered")).isEqualTo(java.util.List.of("RECIPIENT_PAYS"));
        });
        assertThat(offers.quote(town.id(), "EMS", null, GOODS, "ZMW").payer()).isEqualTo("RECIPIENT_PAYS");
        assertThatThrownBy(() -> offers.quote(town.id(), "EMS", "SOMEONE_ELSE", GOODS, "ZMW")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("free delivery: at or above the threshold on the goods total AFTER discount a fee Oriah would collect is waived and says so; below it, and when the threshold is off, it is not")
    void freeDeliveryThreshold() {
        TownView town = freshTown();
        publish(town, "OWN_DELIVERY", "30.00", true, false);

        assertThat(offers.quote(town.id(), "OWN_DELIVERY", null, new BigDecimal("100000.00"), "ZMW").amount()).as("threshold off").isEqualByComparingTo("30.00");

        admin.setThreshold(new BigDecimal("1500.00"), admin.settings().version(), "trial", ADMIN);
        Quote below = offers.quote(town.id(), "OWN_DELIVERY", null, new BigDecimal("1499.99"), "ZMW");
        Quote at = offers.quote(town.id(), "OWN_DELIVERY", null, new BigDecimal("1500.00"), "ZMW");

        assertThat(below.amount()).isEqualByComparingTo("30.00");
        assertThat(below.freeByThreshold()).isFalse();
        assertThat(at.amount()).isEqualByComparingTo("0.00");
        assertThat(at.listAmount()).as("what it would have cost").isEqualByComparingTo("30.00");
        assertThat(at.freeByThreshold()).isTrue();
    }

    @Test
    @DisplayName("self-collect is quoted at no charge for any goods total, with no town and no price")
    void quoteSelfCollect() {
        Quote q = offers.quote(null, "SELF_COLLECT", null, GOODS, "ZMW");

        assertThat(q.optionCode()).isEqualTo("SELF_COLLECT");
        assertThat(q.amount()).isEqualByComparingTo("0.00");
        assertThat(q.priceId()).isNull();
        assertThat(q.townId()).isNull();
        assertThat(q.payer()).isNull();
    }

    @Test
    @DisplayName("a town not yet priced is a distinct answer, 409 `town-not-offered`, never `outside the delivery area`; so is a switched-off town; an unknown town is a 404")
    void townNotOffered() {
        TownView unpriced = freshTown();
        TownView off = freshTown();
        publish(off, "YANGO", "80.00", true, false);
        admin.switchTown(off.id(), false, off.version(), ADMIN);

        assertThatThrownBy(() -> offers.quote(unpriced.id(), "YANGO", null, GOODS, "ZMW")).satisfies(e -> {
            assertThat(problem(e).status().value()).isEqualTo(409);
            assertThat(problem(e).code()).isEqualTo("town-not-offered");
            assertThat(problem(e).properties()).containsEntry("townId", unpriced.id()).containsEntry("optionCode", "YANGO");
        });
        assertThatThrownBy(() -> offers.quote(off.id(), "YANGO", null, GOODS, "ZMW")).satisfies(e -> assertThat(problem(e).code()).isEqualTo("town-not-offered"));
        assertThatThrownBy(() -> offers.quote(UUID.randomUUID(), "YANGO", null, GOODS, "ZMW")).satisfies(e -> {
            assertThat(problem(e).status().value()).isEqualTo(404);
            assertThat(problem(e).code()).isEqualTo("town-not-found");
        });
        assertThatThrownBy(() -> offers.quote(unpriced.id(), null, null, GOODS, "ZMW")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> offers.quote(unpriced.id(), "YANGO", null, GOODS, "USD")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> offers.quote(unpriced.id(), "YANGO", null, new BigDecimal("-1.00"), "ZMW")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a new price changes later quotes only: the older price is kept as history, and each quote says which price it used")
    void aNewPriceChangesLaterQuotesOnly() {
        TownView town = freshTown();
        PriceView first = publish(town, "PLATINUM", "70.00", true, false);
        Quote before = offers.quote(town.id(), "PLATINUM", null, GOODS, "ZMW");

        PriceView second = publish(town, "PLATINUM", "90.00", true, false);
        Quote after = offers.quote(town.id(), "PLATINUM", null, GOODS, "ZMW");

        assertThat(before.amount()).isEqualByComparingTo("70.00");
        assertThat(before.priceId()).isEqualTo(first.id());
        assertThat(after.amount()).isEqualByComparingTo("90.00");
        assertThat(after.priceId()).isEqualTo(second.id());
        assertThat(admin.prices(town.id(), "PLATINUM", "SUPERSEDED")).extracting(PriceView::id).containsExactly(first.id());
    }

    // ---------------------------------------------------------------- own delivery is never paid by the recipient (Phase 8; Q27)

    /** A price published BEFORE the rule existed (a developer's database): written straight in, since the service no longer lets one be made. */
    private UUID publishedBeforeTheRule(TownView town, boolean oriah, boolean recipient) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO delivery_price (id, town_id, option_code, amount, allows_oriah_collects, allows_recipient_pays, status, created_by, published_at, published_by)
                VALUES (?, ?, 'OWN_DELIVERY', 45.00, ?, ?, 'PUBLISHED', 'test:before-the-rule', now(), 'test:before-the-rule')""", id, town.id(), oriah, recipient);
        return id;
    }

    @Test
    @DisplayName("an own-delivery price that once let the recipient pay still never lets them: it is offered as Oriah-collects only, and a quote for the recipient paying is 409 payer-not-offered")
    void anOldOwnDeliveryPriceNeverLetsTheRecipientPay() {
        TownView town = freshTown();
        publishedBeforeTheRule(town, true, true);

        var offered = offeredTown(offers.offers(), town).options().get(0);
        assertThat(offered.code()).isEqualTo("OWN_DELIVERY");
        assertThat(offered.allowsOriahCollects()).isTrue();
        assertThat(offered.allowsRecipientPays()).as("the published row says yes; the rule says no").isFalse();

        assertThatThrownBy(() -> offers.quote(town.id(), "OWN_DELIVERY", "RECIPIENT_PAYS", GOODS, "ZMW")).satisfies(e -> {
            assertThat(problem(e).status().value()).isEqualTo(409);
            assertThat(problem(e).code()).isEqualTo("payer-not-offered");
            assertThat(problem(e).properties()).containsEntry("offered", java.util.List.of("ORIAH_COLLECTS"));
        });
        Quote oriah = offers.quote(town.id(), "OWN_DELIVERY", null, GOODS, "ZMW");
        assertThat(oriah.payer()).isEqualTo("ORIAH_COLLECTS");
        assertThat(oriah.amount()).isEqualByComparingTo("45.00");
    }

    @Test
    @DisplayName("an old own-delivery price that only the recipient could pay is not offered at all, and a quote for it says nothing is offered")
    void anOldRecipientOnlyOwnDeliveryPriceIsNotOffered() {
        TownView town = freshTown();
        publishedBeforeTheRule(town, false, true);

        assertThat(offeredTown(offers.offers(), town)).as("nobody may pay it, so it is not offered").isNull();
        assertThatThrownBy(() -> offers.quote(town.id(), "OWN_DELIVERY", null, GOODS, "ZMW")).satisfies(e -> {
            assertThat(problem(e).status().value()).isEqualTo(409);
            assertThat(problem(e).code()).isEqualTo("payer-not-offered");
        });
    }

    @Test
    @DisplayName("the recipient can still pay a courier: Yango offered to the recipient only is offered and quoted K0 on the order")
    void aCourierMayStillBeRecipientPaid() {
        TownView town = freshTown();
        publish(town, "YANGO", "60.00", false, true);

        var yango = offeredTown(offers.offers(), town).options().get(0);
        assertThat(yango.allowsRecipientPays()).isTrue();
        assertThat(yango.allowsOriahCollects()).isFalse();
        Quote quote = offers.quote(town.id(), "YANGO", null, GOODS, "ZMW");
        assertThat(quote.payer()).isEqualTo("RECIPIENT_PAYS");
        assertThat(quote.amount()).isEqualByComparingTo("0.00");
        assertThat(quote.listAmount()).isEqualByComparingTo("60.00");
    }
}

package com.oriah.promotion.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.oriah.promotion.TestClockConfiguration;
import com.oriah.promotion.TestcontainersConfiguration;
import com.oriah.promotion.exception.PromotionProblem;
import com.oriah.promotion.service.PromotionService.PromotionView;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Promotions as the client runs them (round 5): on a piece, a category or the whole shop; a discount per piece; dates counted in Lusaka days with
 * the last day included. A promotion is never edited: a stop is a dated fact and an edit is a new promotion that says which it replaces, so any
 * past instant has exactly one answer, and a promotion made today never rewrites what a sale made earlier today would have been priced at.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, TestClockConfiguration.class})
@TestPropertySource(properties = {"eureka.client.enabled=false"})
class PromotionServiceTest {

    private static final String ADMIN = "user:kc-maya";
    private static final LocalDate TODAY = LocalDate.parse("2026-11-14");   // Lusaka, at TestClockConfiguration.T0

    @Autowired
    private PromotionService promotions;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void clock() {
        TestClockConfiguration.CLOCK.set(TestClockConfiguration.T0);
    }

    private PromotionView shopWide(String percent, String from, String to) {
        return promotions.create("Shop wide " + UUID.randomUUID().toString().substring(0, 6), "SHOP", null, "PERCENTAGE", new BigDecimal(percent),
                LocalDate.parse(from), LocalDate.parse(to), ADMIN);
    }

    private static PromotionProblem problem(Throwable e) {
        assertThat(e).isInstanceOf(PromotionProblem.class);
        return (PromotionProblem) e;
    }

    private boolean inForce(PromotionView p, String instant) {
        return promotions.inForceAt(Instant.parse(instant)).stream().anyMatch(x -> x.id().equals(p.id()));
    }

    // ---------------------------------------------------------------- creating

    @Test
    @DisplayName("a promotion on one product, a category or the whole shop is created with its discount per piece and its dates, and says who and when")
    void creating() {
        UUID product = UUID.randomUUID();

        PromotionView made = promotions.create("Aurelia week", "PRODUCT", product, "FIXED", new BigDecimal("100.00"), LocalDate.parse("2026-11-20"),
                LocalDate.parse("2026-11-27"), ADMIN);

        assertThat(made.scopeType()).isEqualTo("PRODUCT");
        assertThat(made.scopeId()).isEqualTo(product);
        assertThat(made.discountType()).isEqualTo("FIXED");
        assertThat(made.amount()).isEqualByComparingTo("100.00");
        assertThat(made.startsOn()).isEqualTo(LocalDate.parse("2026-11-20"));
        assertThat(made.endsOn()).isEqualTo(LocalDate.parse("2026-11-27"));
        assertThat(made.createdBy()).isEqualTo(ADMIN);
        assertThat(made.createdAt()).isEqualTo(TestClockConfiguration.T0);
        assertThat(made.stoppedAt()).isNull();
        assertThat(made.replacesId()).isNull();
        assertThat(promotions.get(made.id())).isEqualTo(made);
    }

    @Test
    @DisplayName("a promotion must make sense: a name, a target for a product or category (none for the shop), a whole-ngwee or sensible percentage, days in order, and not starting in the past")
    void validation() {
        UUID target = UUID.randomUUID();
        var from = LocalDate.parse("2026-12-01");
        var to = LocalDate.parse("2026-12-31");

        assertThatThrownBy(() -> promotions.create("  ", "SHOP", null, "PERCENTAGE", BigDecimal.TEN, from, to, ADMIN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> promotions.create("x", "PRODUCT", null, "PERCENTAGE", BigDecimal.TEN, from, to, ADMIN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> promotions.create("x", "SHOP", target, "PERCENTAGE", BigDecimal.TEN, from, to, ADMIN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> promotions.create("x", "WORLD", null, "PERCENTAGE", BigDecimal.TEN, from, to, ADMIN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> promotions.create("x", "SHOP", null, "FIXED", new BigDecimal("10.005"), from, to, ADMIN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> promotions.create("x", "SHOP", null, "FIXED", BigDecimal.ZERO, from, to, ADMIN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> promotions.create("x", "SHOP", null, "PERCENTAGE", new BigDecimal("100.01"), from, to, ADMIN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> promotions.create("x", "SHOP", null, "PERCENTAGE", BigDecimal.TEN, to, from, ADMIN)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> promotions.create("x", "SHOP", null, "PERCENTAGE", BigDecimal.TEN, TODAY.minusDays(1), to, ADMIN))
                .as("history is not rewritten: a promotion cannot start yesterday").isInstanceOf(IllegalArgumentException.class).hasMessageContaining("past");
        promotions.create("starts today", "SHOP", null, "PERCENTAGE", BigDecimal.TEN, TODAY, to, ADMIN);
        promotions.create("hundred percent is allowed", "SHOP", null, "PERCENTAGE", new BigDecimal("100.00"), from, to, ADMIN);
    }

    // ---------------------------------------------------------------- when it is in force

    @Test
    @DisplayName("in force from 00:00 of its first day in Lusaka to the end of its last day: the boundaries are exact, in Lusaka's time, not the server's")
    void theDaysAreLusakaDays() {
        PromotionView p = shopWide("10.00", "2026-11-20", "2026-11-27");

        assertThat(inForce(p, "2026-11-19T21:59:59Z")).as("23:59:59 on the 19th in Lusaka").isFalse();
        assertThat(inForce(p, "2026-11-19T22:00:00Z")).as("00:00 on the 20th in Lusaka").isTrue();
        assertThat(inForce(p, "2026-11-27T21:59:59Z")).as("23:59:59 on the 27th in Lusaka: the last day is included").isTrue();
        assertThat(inForce(p, "2026-11-27T22:00:00Z")).as("00:00 on the 28th").isFalse();
    }

    @Test
    @DisplayName("a promotion made today never reaches back before it existed: a sale an hour earlier is not priced by it")
    void notBeforeItExisted() {
        PromotionView p = shopWide("10.00", "2026-11-14", "2026-11-30");

        assertThat(inForce(p, "2026-11-14T07:59:59Z")).as("a second before it was made").isFalse();
        assertThat(inForce(p, "2026-11-14T08:00:00Z")).isTrue();
        assertThat(promotions.inForceAt(null)).extracting(PromotionView::id).as("null means now").contains(p.id());
    }

    // ---------------------------------------------------------------- stopping and replacing

    @Test
    @DisplayName("stopping ends a promotion from that instant: it was in force before, it is not after, and the history says who")
    void stopping() {
        PromotionView p = shopWide("10.00", "2026-11-14", "2026-11-30");
        TestClockConfiguration.CLOCK.advance(Duration.ofHours(1));

        PromotionView stopped = promotions.stop(p.id(), "user:kc-bea");

        assertThat(stopped.stoppedAt()).isEqualTo(TestClockConfiguration.CLOCK.instant());
        assertThat(stopped.stoppedBy()).isEqualTo("user:kc-bea");
        assertThat(inForce(p, "2026-11-14T08:59:59Z")).as("a second before the stop").isTrue();
        assertThat(inForce(p, "2026-11-14T09:00:00Z")).as("from the stop on").isFalse();
        assertThatThrownBy(() -> promotions.stop(p.id(), ADMIN)).satisfies(e -> {
            assertThat(problem(e).status().value()).isEqualTo(409);
            assertThat(problem(e).code()).isEqualTo("already-stopped");
        });
        assertThatThrownBy(() -> promotions.stop(UUID.randomUUID(), ADMIN)).satisfies(e -> {
            assertThat(problem(e).status().value()).isEqualTo(404);
            assertThat(problem(e).code()).isEqualTo("promotion-not-found");
        });
    }

    @Test
    @DisplayName("an edit is a replacement: the old promotion stops now, the new one says which it replaces, and every earlier instant keeps its answer")
    void replacing() {
        PromotionView first = shopWide("10.00", "2026-11-14", "2026-11-30");
        TestClockConfiguration.CLOCK.advance(Duration.ofHours(2));

        PromotionView second = promotions.replace(first.id(), "Better", "SHOP", null, "PERCENTAGE", new BigDecimal("15.00"), LocalDate.parse("2026-11-14"),
                LocalDate.parse("2026-11-30"), ADMIN);

        assertThat(second.replacesId()).isEqualTo(first.id());
        assertThat(second.amount()).isEqualByComparingTo("15.00");
        assertThat(promotions.get(first.id()).stoppedAt()).isEqualTo(TestClockConfiguration.CLOCK.instant());
        assertThat(inForce(first, "2026-11-14T09:59:59Z")).isTrue();
        assertThat(inForce(second, "2026-11-14T09:59:59Z")).as("the new one did not exist yet").isFalse();
        assertThat(inForce(first, "2026-11-14T10:00:01Z")).isFalse();
        assertThat(inForce(second, "2026-11-14T10:00:01Z")).isTrue();
        assertThatThrownBy(() -> promotions.replace(first.id(), "Again", "SHOP", null, "PERCENTAGE", BigDecimal.TEN, LocalDate.parse("2026-11-15"),
                LocalDate.parse("2026-11-30"), ADMIN)).satisfies(e -> assertThat(problem(e).code()).isEqualTo("already-stopped"));
    }

    // ---------------------------------------------------------------- reading

    @Test
    @DisplayName("the list can be narrowed to a kind of scope or to what is in force now; an unknown promotion is a 404")
    void listing() {
        PromotionView shop = shopWide("10.00", "2026-11-14", "2026-11-30");
        PromotionView later = promotions.create("Later", "CATEGORY", UUID.randomUUID(), "FIXED", new BigDecimal("20.00"), LocalDate.parse("2027-03-01"),
                LocalDate.parse("2027-03-07"), ADMIN);

        assertThat(promotions.list("CATEGORY", null)).extracting(PromotionView::id).contains(later.id()).doesNotContain(shop.id());
        assertThat(promotions.list(null, true)).extracting(PromotionView::id).contains(shop.id()).doesNotContain(later.id());
        assertThat(promotions.list(null, false)).extracting(PromotionView::id).contains(later.id()).doesNotContain(shop.id());
        assertThatThrownBy(() -> promotions.get(UUID.randomUUID())).satisfies(e -> assertThat(problem(e).code()).isEqualTo("promotion-not-found"));
    }
}

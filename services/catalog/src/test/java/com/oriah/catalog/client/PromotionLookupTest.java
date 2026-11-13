package com.oriah.catalog.client;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;

import com.oriah.catalog.StubPromotion;
import com.oriah.catalog.TestcontainersConfiguration;
import com.oriah.catalog.exception.PromotionsUnavailableException;
import com.oriah.catalog.promotion.PromotionDefinition;
import com.oriah.catalog.service.PromotionLookup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Catalog asks promotion what is in force, anonymously (catalog prices for the storefront and holds no token to forward), with the real
 * Feign client against a real HTTP server standing in for promotion. The failure that must never happen is silence: a promotion service
 * that cannot answer, answers slowly or answers nonsense is a 503 for the caller. It is never read as "no promotion in force", which would
 * charge the list price during a promotion and quote it to the customer.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, TestChannelBinderConfiguration.class})
@TestPropertySource(properties = {"spring.cloud.config.enabled=false", "eureka.client.enabled=false"})
class PromotionLookupTest {

    @Autowired
    private PromotionLookup lookup;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @BeforeEach
    @org.junit.jupiter.api.AfterEach
    void fresh() {
        // After as well as before: the stand-in is one server for the whole run, and a test that made it fail must not leave it failing
        // for the next test class, which may never have heard of promotion.
        StubPromotion.reset();
    }

    @Test
    @DisplayName("with nothing in force it answers an empty list, and asks about 'now' by sending no instant")
    void nothingInForce() {
        assertThat(lookup.inForce(null)).isEmpty();

        assertThat(StubPromotion.calls()).hasSize(1);
        assertThat(StubPromotion.calls().getFirst().at()).as("none sent: promotion's own clock says now").isNull();
    }

    @Test
    @DisplayName("what promotion states is read: scope, discount, amount, start and when it was made (more fields than catalog uses are fine)")
    void readsTheDefinitions() {
        UUID product = UUID.randomUUID();
        PromotionDefinition made = StubPromotion.definition("PRODUCT", product, "PERCENTAGE", "12.50", "2026-11-20");
        StubPromotion.promote(made);

        List<PromotionDefinition> read = lookup.inForce(null);

        assertThat(read).hasSize(1);
        PromotionDefinition p = read.getFirst();
        assertThat(p.id()).isEqualTo(made.id());
        assertThat(p.scopeType()).isEqualTo("PRODUCT");
        assertThat(p.scopeId()).isEqualTo(product);
        assertThat(p.discountType()).isEqualTo("PERCENTAGE");
        assertThat(p.amount()).isEqualByComparingTo("12.50");
        assertThat(p.startsOn()).hasToString("2026-11-20");
        assertThat(p.createdAt()).isEqualTo(Instant.parse("2026-11-01T08:00:00Z"));
    }

    @Test
    @DisplayName("an instant asked about is sent as it is, so a paper sale from last week is priced by last week's promotions")
    void asksAboutTheInstantGiven() {
        PromotionDefinition lastWeek = StubPromotion.definition("SHOP", null, "FIXED", "50.00", "2026-11-01");
        StubPromotion.promote(lastWeek, Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2026-11-08T00:00:00Z"));

        assertThat(lookup.inForce(Instant.parse("2026-11-03T10:15:30Z"))).extracting(PromotionDefinition::id).containsExactly(lastWeek.id());
        assertThat(lookup.inForce(Instant.parse("2026-11-09T10:15:30Z"))).isEmpty();
        assertThat(StubPromotion.calls()).extracting(StubPromotion.Call::at).containsExactly("2026-11-03T10:15:30Z", "2026-11-09T10:15:30Z");
    }

    @Test
    @DisplayName("it carries no token: promotion's read is anonymous, and catalog holds nobody's to forward")
    void isAnonymous() {
        lookup.inForce(null);

        assertThat(StubPromotion.calls().getFirst().authorization()).isNull();
    }

    @Test
    @DisplayName("a promotion service that fails is a 503 with Retry-After, never 'no promotion'")
    void aFailureIsA503() {
        StubPromotion.failWith(500);

        assertThatThrownBy(() -> lookup.inForce(null)).isInstanceOf(PromotionsUnavailableException.class).satisfies(e -> {
            ResponseStatusException failure = (ResponseStatusException) e;
            assertThat(failure.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(failure.getHeaders().getFirst("Retry-After")).isEqualTo("5");
        });
    }

    @Test
    @DisplayName("an answer that cannot be read, a refusal and a missing route are all a 503 too: nothing is ever read as 'no promotion'")
    void anythingButARealAnswerIsA503() {
        StubPromotion.answerWith("this is not json");
        assertThatThrownBy(() -> lookup.inForce(null)).isInstanceOf(PromotionsUnavailableException.class);

        StubPromotion.answerWith("{\"at\":\"2026-11-14T08:00:00Z\"}");
        assertThatThrownBy(() -> lookup.inForce(null)).as("a body with no promotions list is not 'none'").isInstanceOf(PromotionsUnavailableException.class);

        StubPromotion.answerWith(null);
        StubPromotion.failWith(404);
        assertThatThrownBy(() -> lookup.inForce(null)).isInstanceOf(PromotionsUnavailableException.class);
        StubPromotion.failWith(403);
        assertThatThrownBy(() -> lookup.inForce(null)).isInstanceOf(PromotionsUnavailableException.class);
    }

    @Test
    @DisplayName("a promotion service slower than a second and a half is a 503, so catalog's own slot (3 to 4 seconds) is never used up")
    void aSlowAnswerIsA503() {
        StubPromotion.delayBy(2500);
        long started = System.nanoTime();

        assertThatThrownBy(() -> lookup.inForce(null)).isInstanceOf(PromotionsUnavailableException.class);

        assertThat(Duration.ofNanos(System.nanoTime() - started)).as("cut off at about 1.5 s, not waited for").isLessThan(Duration.ofMillis(2300));
    }
}

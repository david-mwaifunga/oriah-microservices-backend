package com.oriah.cart.integration;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.oriah.cart.TestcontainersConfiguration;
import com.oriah.cart.model.Cart;
import com.oriah.cart.model.CartItem;
import com.oriah.cart.repository.CartRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What a cart line can hold, against a real Postgres built from the migrations.
 *
 * <h2>Three schema facts, each with a reason to exist</h2>
 *
 * <p><b>A line carries a currency.</b> There was no currency anywhere in cart
 * -- not in either migration, not on {@code Cart}, not on {@code CartItem} --
 * while catalog prices every variant in one and the brand prices in Kwacha.
 * Without this column a degraded bag cannot report what its stored prices are
 * denominated in, and nothing downstream can derive a payment currency from
 * the cart. {@code ddl-auto: validate} means the field and the migration have
 * to land together: with the field and no column, every {@code @SpringBootTest}
 * in cart fails at startup with a schema validation error rather than here.
 *
 * <p><b>A zero price is refused by the database, not just by Java.</b> The
 * aggregate refuses it too ({@code CartCapsTest.anUnpricedLineIsRefused}), so
 * this test goes around the aggregate with a native insert on purpose. Zero is
 * catalog's "nobody has priced this yet" marker for a draft variant, and
 * {@code V1}'s {@code CHECK (unit_price >= 0)} admitted it. Two guards is the
 * right number for a money column: the Java one gives a decent error, the
 * database one survives a future code path that forgets.
 *
 * <p><b>A line title can exceed 255 characters.</b> This one would have been a
 * NEW 500 introduced by this very phase. The plan's own note celebrated
 * removing "the 256-char title that is a DataIntegrityViolationException
 * rendered as a 500 today" -- and then replaced the client's string with
 * catalog's composed {@code variantTitle}, which is the product title (up to
 * 200 characters) plus " - " plus one {@code name + " " + value} pair per
 * option axis, each up to 64. A single option axis therefore reaches ~332
 * characters against a {@code VARCHAR(255)} column: the same 500, now
 * unreachable by the client and triggered by the server's own label. The column
 * is widened to TEXT, matching {@code products.title} in catalog, which is
 * proven to pass {@code ddl-auto: validate} against a plain String field.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false"
})
class LineCurrencyTest {

    @Autowired
    private CartRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    @DisplayName("a line round-trips the currency it was priced in")
    void currencyRoundTrips() {
        Cart cart = new Cart("kc-alice");
        cart.addOrIncrement(UUID.randomUUID(), 2, new BigDecimal("4500.00"), "Aurelia 18k", "ZMW");
        repository.saveAndFlush(cart);

        // findByKeycloakId, not findById: only that method carries
        // @EntityGraph(attributePaths = "items"), so it is the one that can
        // read a line outside a transaction -- and it is the read production
        // actually uses.
        CartItem reloaded = repository.findByKeycloakId("kc-alice").orElseThrow().items().getFirst();
        assertThat(reloaded.currency()).isEqualTo("ZMW");
        assertThat(reloaded.unitPrice()).isEqualByComparingTo("4500.00");
    }

    @Test
    @DisplayName("a blank or absent currency falls back to the brand default rather than null")
    void anAbsentCurrencyBecomesTheBrandDefault() {
        Cart cart = new Cart("kc-bob");
        // The column is NOT NULL, so a null field would be a Hibernate
        // PropertyValueException at flush -- an opaque 500 -- rather than a
        // readable default. Normalising in the entity mirrors catalog's
        // ProductVariant.normaliseCurrency.
        cart.addOrIncrement(UUID.randomUUID(), 1, new BigDecimal("10.00"), "Lume", null);
        repository.saveAndFlush(cart);

        assertThat(repository.findByKeycloakId("kc-bob").orElseThrow().items().getFirst().currency())
                .isEqualTo(CartItem.DEFAULT_CURRENCY);
    }

    @Test
    @DisplayName("the database refuses a zero-priced line even when Java does not")
    void aZeroPricedLineIsRefusedByTheDatabaseToo() {
        Cart cart = repository.saveAndFlush(new Cart("kc-carol"));

        // Deliberately around the aggregate: this pins the CHECK constraint,
        // which is the guard that survives a future code path forgetting the
        // Java one.
        assertThatThrownBy(() -> jdbc.update("""
                        INSERT INTO cart_items
                            (id, cart_id, variant_id, quantity, unit_price, title, currency,
                             added_at, created_at, updated_at, is_deleted)
                        VALUES (?, ?, ?, 1, 0.00, 'Draft ring', 'ZMW', now(), now(), now(), false)
                        """,
                UUID.randomUUID(), cart.id(), UUID.randomUUID()))
                .as("V1 admitted unit_price >= 0; zero is catalog's 'not priced yet' marker")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a 300-character line title persists: catalog's composed variantTitle exceeds 255")
    void aLongVariantTitleRoundTrips() {
        // 300 is comfortably past the old VARCHAR(255) and comfortably short of
        // what catalog can actually compose (~332 with one option axis).
        String longTitle = "Aurelia ".repeat(37) + "ring";
        assertThat(longTitle.length()).isGreaterThan(255);

        Cart cart = new Cart("kc-dave");
        cart.addOrIncrement(UUID.randomUUID(), 1, new BigDecimal("4500.00"), longTitle, "ZMW");
        repository.saveAndFlush(cart);

        assertThat(repository.findByKeycloakId("kc-dave").orElseThrow().items().getFirst().title())
                .as("the server's own composed label must not be the thing that 500s")
                .isEqualTo(longTitle);
    }
}

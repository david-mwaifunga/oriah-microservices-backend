package com.oriah.order;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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

import com.oriah.jpa.BaseEntity;
import com.oriah.order.model.Order;
import com.oriah.order.model.OrderStatus;
import com.oriah.order.repository.OrderRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * What JPA auditing actually does to an {@code orders} row.
 *
 * <p>Written <strong>before</strong> the Tier 6 rename so it is the safety net
 * the rename gets checked against, and written from measurement rather than
 * from reading the annotations — which turned out to matter, because the
 * annotations describe something the database does not do.
 *
 * <h2>Two things a throwaway diagnostic established, after two wrong guesses</h2>
 *
 * <p><strong>1. {@code Order}'s own field is the persisted one, so
 * {@code @LastModifiedDate} is inert here.</strong> {@link Order} declares its
 * own {@code updatedAt} mapped to {@code updated_at} — the same column
 * {@link BaseEntity} maps with {@code @LastModifiedDate}. Hibernate does
 * not raise "Repeated column in mapping"; the subclass field shadows the
 * superclass one as the persistent attribute. The listener still writes the
 * superclass field, which is never persisted and comes back
 * <strong>null</strong> after a reload.
 *
 * <p>Nothing is user-visible today, and only because {@code Order} maintains
 * the column by hand in {@code transitionTo()} and
 * {@code attachPaymentIntent()}. The trap is that it looks audited and is not:
 * delete those two assignments trusting the annotation one class up, and a
 * {@code NOT NULL} column silently stops advancing.
 *
 * <p><strong>2. The mixed timestamp types are NOT a correctness bug, which took
 * measuring to establish.</strong> Audit columns are inconsistent across the
 * platform — {@code orders.created_at} is {@code timestamp without time zone}
 * while {@code orders.updated_at} is {@code TIMESTAMPTZ}, because
 * {@code V2__add_audit_columns.sql} uses {@code ADD COLUMN IF NOT EXISTS} and
 * {@code V1} had already created one of them. That looked like a live bug: a
 * diagnostic on an {@code Africa/Johannesburg} JVM showed {@code 15:06:06Z} in
 * the entity and {@code 13:06:06Z} back from the column.
 *
 * <p>It was an artefact of the diagnostic, not of the application. A raw JDBC
 * {@code getObject(.., Instant.class)} against {@code timestamp without time
 * zone} applies the JVM's zone to the stored digits; Hibernate's own
 * {@code Instant} mapping does not — it writes and reads the UTC digits
 * directly. So the application round-trips correctly on any JVM zone, and
 * {@link #createdAtIsStoredAsAnInstantNotLocalWallClock} is the assertion that
 * holds it that way.
 *
 * <p>Worth recording because the trap is easy to fall into twice: a test that
 * reads an audit column with plain JDBC and compares it to the entity will
 * manufacture an apparent whole-timezone bug. The type inconsistency is real
 * but cosmetic, so Tier 6 ships no migration for it.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@TestPropertySource(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false"
})
class AuditingBehaviourTest {

    @Autowired
    private OrderRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    @MockitoBean
    @SuppressWarnings("unused")
    private JwtDecoder jwtDecoder;

    private Order saveOrder() {
        Order o = new Order("kc-audit-" + UUID.randomUUID(), UUID.randomUUID(), "USD", "SELF_COLLECT", java.math.BigDecimal.ZERO);
        o.addItem(UUID.randomUUID(), "Aurelia", 1, new java.math.BigDecimal("100.00"));
        return repository.saveAndFlush(o);
    }

    /**
     * One microsecond of slack, which is the only correct way to compare an
     * {@code Instant} against a Postgres round-trip.
     *
     * <p>Postgres stores timestamps to <strong>microsecond</strong> precision
     * while {@code Instant.now()} produces nanoseconds — and it
     * <strong>rounds</strong> rather than truncating:
     * {@code ...057335600Z} comes back as {@code ...057336Z}, a microsecond
     * LATER than the value written.
     *
     * <p>Both naive approaches therefore fail. Plain {@code isEqualTo} fails on
     * the lost nanoseconds; {@code truncatedTo(MICROS)} fails whenever the
     * nanosecond part rounds up, which is roughly half the time — a flaky test
     * that looks like a real defect when it fires. This cost two wrong
     * diagnoses before the rounding was the thing actually measured.
     */
    private static final org.assertj.core.data.TemporalUnitOffset ONE_MICRO =
            within(1, ChronoUnit.MICROS);

    /**
     * Reads the shadowed superclass field.
     *
     * <p>A cast cannot do this. {@code Order} overrides {@code updatedAt()}, so
     * {@code ((BaseEntity) o).updatedAt()} dispatches virtually straight
     * back to {@code Order.updatedAt()} — the cast changes the static type, not
     * the target. Believing otherwise is what produced two contradictory
     * readings before the diagnostic.
     */
    private static Instant shadowedSuperclassField(Order o) {
        try {
            Field f = BaseEntity.class.getDeclaredField("updatedAt");
            f.setAccessible(true);
            return (Instant) f.get(o);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("BaseEntity.updatedAt no longer exists", e);
        }
    }

    // ------------------------------------------------------------------
    // Auditing is wired at all -- the control case
    // ------------------------------------------------------------------

    @Test
    @DisplayName("@CreatedDate and @CreatedBy are populated by auditing")
    void createdColumnsArePopulatedByAuditing() {
        Order saved = saveOrder();

        // created_at and created_by are NOT shadowed -- Order declares no
        // fields for them -- so these are the inherited attributes doing their
        // job. This is the control: it shows @EnableJpaAuditing and the
        // AuditorAware bean are wired, which is what makes the inert
        // @LastModifiedDate below a finding rather than "auditing is off".
        assertThat(saved.createdAt())
                .as("if this is null, auditing is not wired at all and every other "
                        + "assertion here is meaningless")
                .isNotNull();
        assertThat(saved.createdBy())
                .as("created_by must be written even with no authenticated principal; "
                        + "AuditAwareImpl falls back outside a request")
                .isNotNull();
    }

    // ------------------------------------------------------------------
    // Finding 1: @LastModifiedDate is inert on Order
    // ------------------------------------------------------------------

    @Test
    @DisplayName("updated_at is Order's OWN field; the @LastModifiedDate one is never persisted")
    void ordersUpdatedAtIsTheSubclassFieldNotTheAuditedOne() {
        Order saved = saveOrder();
        saved.transitionTo(OrderStatus.PAID);
        Order updated = repository.saveAndFlush(saved);

        Instant inColumn = jdbc.queryForObject(
                "select updated_at from orders where id = ?", Instant.class, updated.id());

        assertThat(inColumn)
                .as("the column holds Order's own field, set by transitionTo()")
                .isCloseTo(updated.updatedAt(), ONE_MICRO);

        assertThat(java.time.Duration.between(
                        shadowedSuperclassField(updated), inColumn).abs().toNanos())
                .as("while the @LastModifiedDate field holds a DIFFERENT instant -- the "
                        + "listener ran at flush time, wrote the shadowed field, and "
                        + "Hibernate ignored it. Compared as a duration because the two "
                        + "are milliseconds apart, far beyond rounding. If this ever "
                        + "drops to zero the duplicate mapping has been removed and this "
                        + "test should go with it.")
                .isGreaterThan(1_000L);
    }

    @Test
    @DisplayName("after a reload the shadowed field is null, proving which one Hibernate hydrates")
    void theShadowedFieldIsNotHydrated() {
        Order saved = saveOrder();
        saved.transitionTo(OrderStatus.PAID);
        repository.saveAndFlush(saved);
        UUID id = saved.id();

        // Detach everything, so the next read is a real load rather than the
        // first-level cache handing back the instance just mutated.
        entityManager.clear();
        Order reloaded = repository.findById(id).orElseThrow();

        assertThat(reloaded.updatedAt())
                .as("Order's field is the persistent attribute, so it hydrates -- which "
                        + "is why the API's updatedAt is correct despite all of the above")
                .isNotNull();
        assertThat(shadowedSuperclassField(reloaded))
                .as("and the @LastModifiedDate field is left unset on load: it is dead "
                        + "state, not a second source of truth")
                .isNull();
    }

    // ------------------------------------------------------------------
    // Finding 2: created_at is stored in the JVM's zone, not UTC
    // ------------------------------------------------------------------

    @Test
    @DisplayName("created_at round-trips as the same INSTANT despite being a naive timestamp")
    void createdAtIsStoredAsAnInstantNotLocalWallClock() {
        // Guards the conclusion that the mixed column types are harmless.
        //
        // orders.created_at is `timestamp without time zone`, which stores no
        // offset -- so whether an Instant survives depends entirely on both
        // sides agreeing to treat the digits as UTC. Hibernate does. This test
        // asserts the digits ARE the instant, on whatever zone the JVM happens
        // to run, which is what makes a migration to TIMESTAMPTZ unnecessary
        // rather than merely deferred.
        //
        // It also pins the trap: reading this column with a raw JDBC
        // getObject(.., Instant.class) applies the JVM zone and reports a value
        // a whole offset out. Comparing THAT to the entity is what produced a
        // convincing but false two-hour bug.
        Order saved = saveOrder();
        Instant fromEntity = saved.createdAt();

        // Read the column as a LOCAL date-time: exactly the digits stored, with
        // no zone conversion applied by the driver.
        java.time.LocalDateTime storedDigits = jdbc.queryForObject(
                "select created_at from orders where id = ?",
                java.time.LocalDateTime.class, saved.id());

        assertThat(storedDigits).isNotNull();
        assertThat(storedDigits.toInstant(ZoneOffset.UTC))
                .as("the digits in created_at must BE the instant, read on a %s JVM. "
                                + "If this is off by a whole zone offset, something has "
                                + "started writing local wall-clock into a naive timestamp "
                                + "column and the audit trail no longer means one thing. "
                                + "The entity said %s.",
                        java.time.ZoneId.systemDefault(), fromEntity)
                .isCloseTo(fromEntity, ONE_MICRO);
    }

    // ------------------------------------------------------------------
    // Soft delete
    // ------------------------------------------------------------------

    @Test
    @DisplayName("is_deleted defaults to false and soft-delete flips it")
    void softDeleteFlagRoundTrips() {
        Order saved = saveOrder();
        assertThat(saved.isDeleted()).isFalse();

        saved.markDeleted();
        repository.saveAndFlush(saved);

        Boolean flag = jdbc.queryForObject(
                "select is_deleted from orders where id = ?", Boolean.class, saved.id());
        assertThat(flag).isTrue();
    }
}

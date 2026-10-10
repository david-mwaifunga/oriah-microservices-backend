package com.oriah.jpa;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;

import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Shared JPA superclass that gives every Oriah entity five
 * cross-cutting columns:
 *
 * <ul>
 *   <li>{@code created_at}, {@code updated_at} -- timestamps managed
 *       by Spring Data's {@link AuditingEntityListener}.</li>
 *   <li>{@code created_by}, {@code updated_by} -- Keycloak {@code sub}
 *       claim resolved via the {@code auditor} bean
 *       ({@link AuditAwareImpl}); falls back to "system" for
 *       writes from Kafka-consumer threads where the
 *       {@code SecurityContext} is empty.</li>
 *   <li>{@code is_deleted} -- soft-delete flag. Services opt into
 *       soft delete by calling {@link #markDeleted()} on the entity
 *       and saving (JPA dirty-checking persists the flip).
 *       Repository code that wants to surface only live rows queries
 *       {@code WHERE is_deleted = false}. {@code repository.delete(x)}
 *       still hard-deletes -- that semantic is preserved on purpose so
 *       existing {@code @BeforeEach repository.deleteAll()} test
 *       fixtures continue to actually drop rows (Hibernate's
 *       {@code @SoftDelete} would otherwise turn that into a no-op
 *       update and collide with unique constraints on subsequent
 *       inserts).</li>
 * </ul>
 *
 * <p>{@code created_at} and {@code created_by} are
 * {@code updatable = false} so JPA-managed updates cannot rewrite
 * them. {@code created_at} is also nullable so {@code @DataJpaTest}
 * slices keep working without {@code @Import}-ing the auditing config
 * -- the listener simply does not fire in those slices, and a null
 * is fine.
 *
 * <p>Activation: a per-service {@code JpaConfig} {@code @Configuration}
 * class is responsible for {@code @EnableJpaAuditing(auditorAwareRef =
 * "auditor")}. We intentionally do NOT auto-configure auditing from
 * this jar (see PROGRESS.md "Why T1.6 was harder than expected" for
 * why the per-service shape avoids @WebMvcTest slice failures and
 * spring-cloud-openfeign classpath conflicts).
 *
 * <p>A Flyway migration per service must add the five columns; this
 * jar does not touch the DB. See any service's
 * {@code V<n>__add_audit_columns.sql} for the shape.
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity {

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private Instant updatedAt;

    @CreatedBy
    @Column(name = "created_by", length = 64, updatable = false)
    private String createdBy;

    @LastModifiedBy
    @Column(name = "updated_by", length = 64)
    private String updatedBy;

    @Column(name = "is_deleted", nullable = false)
    private boolean deleted = false;

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public String createdBy() {
        return createdBy;
    }

    public String updatedBy() {
        return updatedBy;
    }

    public boolean isDeleted() {
        return deleted;
    }

    /**
     * Flip the soft-delete flag. The caller is responsible for
     * saving the entity (or running inside a {@code @Transactional}
     * method where JPA dirty-checking does it on commit).
     *
     * <p>The audited columns ({@code updated_at}, {@code updated_by})
     * refresh on the same flush, giving each row a clear "soft-
     * deleted on X by Y" trail.
     */
    public void markDeleted() {
        this.deleted = true;
    }

    /** Inverse of {@link #markDeleted()} -- restore a soft-deleted row. */
    public void restore() {
        this.deleted = false;
    }
}

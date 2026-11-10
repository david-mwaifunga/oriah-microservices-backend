package com.oriah.procurement.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.oriah.procurement.model.Supplier;

/**
 * Suppliers, live ones only unless a method says otherwise.
 *
 * <p>Soft delete is not filtered automatically: {@code BaseEntity} marks a row
 * deleted but nothing hides it, and {@code delete()} on a repository still
 * removes the row for good. So every read here says {@code deleted = false}
 * itself, and a deleted supplier simply does not exist as far as the API is
 * concerned (its row is kept so Phase 4's history can still name it).
 *
 * <p>Names are compared with {@code lower(...)}, the same expression the partial
 * unique index {@code uq_suppliers_name_live} is built on, so "is it taken?" and
 * "will the database refuse it?" always agree.
 */
public interface SupplierRepository extends JpaRepository<Supplier, UUID> {

    Optional<Supplier> findByIdAndDeletedFalse(UUID id);

    /** Is a live supplier already called this, in any letter case? */
    @Query("select count(s) > 0 from Supplier s where lower(s.name) = lower(:name) and s.deleted = false")
    boolean existsLiveName(@Param("name") String name);

    /**
     * Is a live supplier <em>other than this one</em> already called this, in any
     * letter case? For a rename: a supplier is not in conflict with itself, so
     * changing "Fycargo" to "FYCARGO" is fine.
     */
    @Query("select count(s) > 0 from Supplier s where lower(s.name) = lower(:name) and s.deleted = false "
            + "and s.id <> :id")
    boolean existsOtherLiveName(@Param("name") String name, @Param("id") UUID id);

    // The list, in four explicit queries instead of one with optional parameters:
    // "(:active is null or s.active = :active)" makes PostgreSQL guess the type of a
    // null parameter, which it cannot always do, and a query per combination is
    // plain to read. Every one orders by name ignoring letter case, then id, so a
    // page of a growing list never shuffles. Names are compared with lower(...),
    // as the unique index does. In a search pattern '!' is the escape character
    // (the service escapes %, _ and ! in what the caller typed), so those three
    // characters are ordinary text, not wildcards.

    @Query("select s from Supplier s where s.deleted = false order by lower(s.name), s.id")
    Page<Supplier> findLive(Pageable pageable);

    @Query("select s from Supplier s where s.deleted = false and s.active = :active "
            + "order by lower(s.name), s.id")
    Page<Supplier> findLiveByActive(@Param("active") boolean active, Pageable pageable);

    @Query("select s from Supplier s where s.deleted = false and lower(s.name) like lower(:pattern) escape '!' "
            + "order by lower(s.name), s.id")
    Page<Supplier> findLiveByName(@Param("pattern") String pattern, Pageable pageable);

    @Query("select s from Supplier s where s.deleted = false and s.active = :active "
            + "and lower(s.name) like lower(:pattern) escape '!' order by lower(s.name), s.id")
    Page<Supplier> findLiveByActiveAndName(@Param("active") boolean active, @Param("pattern") String pattern,
                                           Pageable pageable);
}

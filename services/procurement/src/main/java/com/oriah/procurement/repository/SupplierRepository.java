package com.oriah.procurement.repository;

import java.util.Optional;
import java.util.UUID;

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
}

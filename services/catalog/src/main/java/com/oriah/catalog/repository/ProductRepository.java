package com.oriah.catalog.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.oriah.catalog.model.Product;

public interface ProductRepository
        extends JpaRepository<Product, UUID>, ProductStorefrontQueries, ProductAdminQueries {

    /**
     * The product, with its row locked until the transaction ends
     * (PESSIMISTIC_WRITE: {@code SELECT ... FOR UPDATE}).
     *
     * <p>Every write that records a snapshot of the product takes this
     * first. Two writes to one product then run one after the other: the
     * second waits here until the first commits, and only then reads the
     * product -- with the first change in it. Without it, two staff
     * repricing two sizes of one ring at once each recorded a snapshot that
     * missed the other's price, and the one numbered last (which every
     * consumer keeps) showed a price the database no longer held.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.id = :id")
    Optional<Product> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Products whose breadcrumb trail points at this category.
     *
     * <p>Needed when a category is deleted. {@code product_categories} is
     * {@code ON DELETE CASCADE} so the MEMBERSHIP rows go on their own, but
     * {@code products.primary_category_id} is a plain reference -- deleting a
     * category some product uses as its primary violates it, and the operator
     * sees a 409 for a taxonomy edit that should simply have worked.
     */
    List<Product> findByPrimaryCategoryId(UUID categoryId);

    /**
     * Used to resolve a slug collision before insert.
     *
     * <p>This is a check-then-act, so it does not make the insert safe on its
     * own -- two concurrent creates of the same title would both see "free"
     * and one would break the UNIQUE constraint. ProductService therefore
     * makes creates take turns (a transaction-scoped lock taken before this
     * check), so the second sees the first's slug as taken. The UNIQUE
     * constraint remains the backstop.
     */
    boolean existsBySlug(String slug);

    // findOptionallyFilteredByTag lived here and is gone with its only caller.
    // The storefront query handles the tag filter alongside category, type and
    // price, and it also excludes DRAFT and ARCHIVED products -- which this
    // method did not. Keeping both would have left a second list path that
    // quietly returns unpublished, unpriced products, for a future caller to
    // choose by accident.
}

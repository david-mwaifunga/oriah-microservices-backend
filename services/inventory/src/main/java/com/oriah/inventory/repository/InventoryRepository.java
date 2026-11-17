package com.oriah.inventory.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.oriah.inventory.dto.AdminInventoryRow;
import com.oriah.inventory.model.Inventory;

public interface InventoryRepository extends JpaRepository<Inventory, UUID> {

    /**
     * The stock row, read WITH its row lock: what a receipt does before it checks the count against the lots, so that
     * nothing can commit between the read and the check.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Inventory i where i.variantId = :variantId")
    Optional<Inventory> findByIdForUpdate(@Param("variantId") UUID variantId);

    /**
     * Several stock rows, each WITH its row lock, taken in variant-id order: the order every path that holds more than one variant uses, so two
     * of them can never wait on each other (a hold touching the ring then the box, and another the box then the ring, would deadlock otherwise).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Inventory i where i.variantId in :ids order by i.variantId")
    List<Inventory> findAllForUpdate(@Param("ids") Collection<UUID> ids);

    /**
     * The admin list: stock joined to the catalog title, scarcest first.
     *
     * <p>A LEFT join, so a variant whose product event has not arrived yet
     * still appears with a null title. An inner join would hide exactly the
     * rows most likely to be wrong.
     *
     * <p>Ordered by {@code onHand - reserved} ascending because the first
     * thing an inventory admin wants is what is about to run out; variant id
     * is the tiebreak so paging is stable. {@code available} is computed, not
     * a column, so the ordering and the filter are both arithmetic.
     *
     * <p>{@code :outOfStock} is a nullable flag rather than two methods: when
     * null the predicate collapses to true and the same query serves the
     * unfiltered list.
     */
    @Query("""
            select new com.oriah.inventory.dto.AdminInventoryRow(
                       i.variantId, p.title, i.onHand, i.reserved,
                       i.onHand - i.reserved - i.committed - i.quarantined, i.committed, i.onHand - i.reserved - i.committed - i.quarantined,
                       i.quarantined)
            from Inventory i
            left join ProductRef p on p.variantId = i.variantId
            where i.deleted = false
              and (:outOfStock is null
                   or (:outOfStock = true  and i.onHand - i.reserved - i.committed - i.quarantined <= 0)
                   or (:outOfStock = false and i.onHand - i.reserved - i.committed - i.quarantined > 0))
            order by i.onHand - i.reserved - i.committed - i.quarantined asc, i.variantId asc
            """)
    Page<AdminInventoryRow> findAdminRows(@Param("outOfStock") Boolean outOfStock,
                                          Pageable pageable);
}

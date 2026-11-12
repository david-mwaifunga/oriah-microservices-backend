package com.oriah.procurement.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.oriah.procurement.model.PurchaseOrder;
import com.oriah.procurement.model.PurchaseOrderStatus;

import jakarta.persistence.LockModeType;

/**
 * Purchase orders. Nothing is soft-deleted here (an order is cancelled, never deleted), so
 * there is no live/deleted split like the suppliers have.
 *
 * <p>Every list is newest first by reference number, which the database assigns in order,
 * so a page of a growing list never shuffles. Four queries, not one with optional
 * parameters: a null parameter makes PostgreSQL guess its type, which it cannot always do.
 */
public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, UUID> {

    /**
     * The order with its row locked until the transaction ends ({@code SELECT ... FOR UPDATE}).
     * Everything that changes an order, and everything that attaches or receives it, takes this
     * first, so two of them run one after the other and the second reads what the first wrote.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PurchaseOrder p where p.id = :id")
    Optional<PurchaseOrder> findByIdForUpdate(@Param("id") UUID id);

    /** Does any purchase order, of any status, name this supplier? The reason a supplier cannot be deleted. */
    boolean existsBySupplierId(UUID supplierId);

    @Query("select p from PurchaseOrder p order by p.refNo desc")
    Page<PurchaseOrder> findNewestFirst(Pageable pageable);

    @Query("select p from PurchaseOrder p where p.supplierId = :supplierId order by p.refNo desc")
    Page<PurchaseOrder> findBySupplier(@Param("supplierId") UUID supplierId, Pageable pageable);

    @Query("select p from PurchaseOrder p where p.status = :status order by p.refNo desc")
    Page<PurchaseOrder> findByStatus(@Param("status") PurchaseOrderStatus status, Pageable pageable);

    @Query("select p from PurchaseOrder p where p.supplierId = :supplierId and p.status = :status order by p.refNo desc")
    Page<PurchaseOrder> findBySupplierAndStatus(@Param("supplierId") UUID supplierId,
                                                @Param("status") PurchaseOrderStatus status, Pageable pageable);
}

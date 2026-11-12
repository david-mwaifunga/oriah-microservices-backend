package com.oriah.procurement.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.oriah.procurement.model.Consignment;
import com.oriah.procurement.model.ConsignmentStatus;

import jakarta.persistence.LockModeType;

/**
 * Consignments. A draft is deleted for real; a received consignment is never deleted (the
 * database refuses it), so there is no live/deleted split here.
 *
 * <p>Every list is newest first by reference number, which the database assigns in order, so a
 * page of a growing list never shuffles. Four queries, not one with optional parameters: a null
 * parameter makes PostgreSQL guess its type, which it cannot always do.
 */
public interface ConsignmentRepository extends JpaRepository<Consignment, UUID> {

    /**
     * The consignment with its row locked until the transaction ends ({@code SELECT ... FOR
     * UPDATE}). Everything that changes a consignment takes this first, so two changes run one
     * after the other and the second reads what the first wrote. Lock order is consignment
     * first, then its purchase orders in id order.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Consignment c where c.id = :id")
    Optional<Consignment> findByIdForUpdate(@Param("id") UUID id);

    @Query("select c from Consignment c order by c.refNo desc")
    Page<Consignment> findNewestFirst(Pageable pageable);

    @Query("select c from Consignment c where c.status = :status order by c.refNo desc")
    Page<Consignment> findByStatus(@Param("status") ConsignmentStatus status, Pageable pageable);

    @Query("select c from Consignment c where c.opening = :opening order by c.refNo desc")
    Page<Consignment> findByOpening(@Param("opening") boolean opening, Pageable pageable);

    @Query("select c from Consignment c where c.status = :status and c.opening = :opening order by c.refNo desc")
    Page<Consignment> findByStatusAndOpening(@Param("status") ConsignmentStatus status,
                                             @Param("opening") boolean opening, Pageable pageable);
}

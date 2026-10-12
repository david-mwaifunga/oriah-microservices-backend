package com.oriah.catalog.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.oriah.catalog.model.ProductImage;

public interface ProductImageRepository extends JpaRepository<ProductImage, UUID> {

    /**
     * An image with its product fetched, for the serve path.
     *
     * <p>The fetch join is required, not an optimisation: serving an image
     * depends on the owning product's STATUS -- a DRAFT product's images must
     * not be publicly readable -- and resolving that lazily would mean a
     * second query on every image request, which is the highest-volume
     * endpoint in the catalogue.
     */
    @Query("SELECT i FROM ProductImage i JOIN FETCH i.product WHERE i.id = :id")
    Optional<ProductImage> findByIdWithProduct(@Param("id") UUID id);

    /** Used to make a re-upload of identical bytes idempotent. */
    @Query("SELECT i FROM ProductImage i WHERE i.product.id = :productId AND i.contentHash = :hash")
    Optional<ProductImage> findByProductAndHash(@Param("productId") UUID productId,
                                                @Param("hash") String hash);

    /**
     * Storage keys for a product, collected before its rows are deleted.
     *
     * <p>Read inside the deleting transaction, used after it commits: object
     * storage is not transactional, so the keys have to be captured while the
     * rows still exist and the objects removed only once the database change
     * is durable. Deleting objects first would lose bytes if the transaction
     * then rolled back.
     */
    @Query("SELECT i.storageKey FROM ProductImage i WHERE i.product.id = :productId")
    List<String> findStorageKeysByProduct(@Param("productId") UUID productId);
}

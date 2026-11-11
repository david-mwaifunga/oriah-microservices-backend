package com.oriah.catalog.service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.oriah.catalog.dto.ProductImageResponse;
import com.oriah.catalog.model.Product;
import com.oriah.catalog.model.ProductImage;
import com.oriah.catalog.model.ProductStatus;
import com.oriah.catalog.repository.ProductImageRepository;
import com.oriah.catalog.repository.ProductRepository;
import com.oriah.catalog.storage.IngestGate;
import com.oriah.catalog.storage.ProductImageIngest;
import com.oriah.catalog.storage.ProductImageStorage;
import com.oriah.catalog.storage.StoredImage;
import com.oriah.observability.ResourceNotFoundException;

import lombok.extern.slf4j.Slf4j;

/**
 * Upload, serve and delete product images.
 *
 * <h2>The database and the object store are not one transaction</h2>
 *
 * They cannot be: storage has no transaction to enlist in. So the ordering is
 * chosen to make the surviving failure mode the harmless one in each
 * direction.
 *
 * <p>On UPLOAD the object is written first, then the row. A crash between them
 * leaves an object nobody references — wasted bytes, reclaimable, invisible to
 * customers. The reverse order would leave a row whose image 404s, which is a
 * broken gallery on a live storefront.
 *
 * <p>On DELETE the row goes first and the object only after the transaction
 * COMMITS. A crash between them leaves the same harmless orphan. Deleting the
 * object first would destroy bytes that a rolled-back transaction still
 * references, and the image would then be permanently broken with the row
 * intact.
 */
@Service
@Slf4j
public class DefaultProductImageService implements ProductImageService {

    private final ProductRepository products;
    private final ProductImageRepository images;
    private final ProductImageStorage storage;
    private final IngestGate gate;
    private final TransactionTemplate transaction;

    public DefaultProductImageService(ProductRepository products,
                                      ProductImageRepository images,
                                      ProductImageStorage storage,
                                      IngestGate gate,
                                      PlatformTransactionManager transactionManager) {
        this.products = products;
        this.images = images;
        this.storage = storage;
        this.gate = gate;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * Not {@code @Transactional}, on purpose. The picture is decoded, turned upright and
     * re-encoded first, and that can wait for working memory behind other uploads
     * ({@link IngestGate}). A transaction opened before it would hold a database connection
     * for the whole of that wait, and a few queued admin uploads could then use up the pool
     * the storefront reads from. So: a cheap look at the product (404 before any memory is
     * spent), the picture work with no transaction open, and only then one short transaction
     * that writes the object and the row.
     */
    @Override
    public ProductImageResponse upload(UUID productId, byte[] bytes, String filename,
                                       String declaredContentType, String altText) {
        requireLiveProduct(productId);

        // Validated and re-encoded BEFORE anything is written, so a rejected
        // upload leaves no half-created state to clean up.
        StoredImage prepared = ProductImageIngest.accept(
                productId, bytes, filename, declaredContentType, gate::admit);

        return transaction.execute(status -> store(productId, prepared, altText));
    }

    /**
     * A deleted product is not found: its objects were swept when it was
     * deleted, so new bytes would be stored for a product nobody can see.
     */
    private Product requireLiveProduct(UUID productId) {
        return products.findById(productId)
                .filter(p -> !p.isDeleted())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Product", "id", productId.toString()));
    }

    private ProductImageResponse store(UUID productId, StoredImage prepared, String altText) {
        // Read again inside the transaction: the product may have been deleted while the
        // picture was being worked on, and this is the copy the new row attaches to.
        Product product = requireLiveProduct(productId);

        // Content-addressed, so an admin who clicks upload twice gets the
        // existing image back rather than a second identical gallery entry.
        // Checked here for a clean answer; UNIQUE (product_id, content_hash)
        // is what actually guarantees it against two concurrent uploads.
        Optional<ProductImage> existing =
                images.findByProductAndHash(productId, prepared.contentHash());
        if (existing.isPresent()) {
            ProductImage image = existing.get();
            log.debug("image already present for product={} hash={}", productId,
                    prepared.contentHash());
            return toResponse(image);
        }

        // Object first: see the class javadoc on failure ordering.
        storage.put(prepared.storageKey(), prepared.bytes(), prepared.contentType());

        var image = new ProductImage(
                product,
                prepared.storageKey(),
                prepared.contentHash(),
                prepared.contentType(),
                prepared.width(),
                prepared.height(),
                prepared.byteSize(),
                altText);
        product.addImage(image);
        products.saveAndFlush(product);

        log.info("Uploaded image id={} product={} {}x{} {} bytes",
                image.id(), productId, image.width(), image.height(), image.byteSize());
        return toResponse(image);
    }

    /**
     * {@inheritDoc}
     *
     * <p>Empty unless the owning product is {@link ProductStatus#ACTIVE}.
     * Hiding an unpublished product from the list and the detail endpoint is
     * not enough on its own: its photographs would still be readable by id,
     * and image ids are not secret — they are returned by the admin API the
     * operator is working in and appear in any page that has ever rendered.
     * An unreleased design leaking through its image URL is the same disclosure
     * as leaking the product.
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<ServedImage> servePublicly(UUID imageId) {
        return images.findByIdWithProduct(imageId)
                .filter(image -> !image.product().isDeleted())
                .filter(image -> image.product().status() == ProductStatus.ACTIVE)
                .flatMap(this::openFor);
    }

    /** No status filter: reviewing a draft's photographs is the point. */
    @Override
    @Transactional(readOnly = true)
    public Optional<ServedImage> serveForAdmin(UUID imageId) {
        return images.findByIdWithProduct(imageId)
                // The admin may review a DRAFT's photographs, but not a
                // deleted product's: its objects have already been reclaimed,
                // so this would 404 from storage anyway -- better to say so
                // from the row than to log a missing-object warning for an
                // expected state.
                .filter(image -> !image.product().isDeleted())
                .flatMap(this::openFor);
    }

    private Optional<ServedImage> openFor(ProductImage image) {
        return storage.open(image.storageKey())
                .map(content -> new ServedImage(
                        content,
                        image.contentType(),
                        image.byteSize(),
                        // The ETag comes from this column, never from the
                        // object store -- HeadObject was measured returning no
                        // ETag at all during the storage evaluation, so there
                        // is nothing to forward even where one exists.
                        image.contentHash()))
                .or(() -> {
                    // The row exists and the object does not. Reachable after
                    // a delete whose post-commit storage call failed, so it is
                    // logged as a reconciliation item rather than an error --
                    // the customer gets a 404 either way.
                    log.warn("image row {} references missing object key={}",
                            image.id(), image.storageKey());
                    return Optional.empty();
                });
    }

    @Override
    @Transactional
    public boolean delete(UUID productId, UUID imageId) {
        // A deleted product's image rows are kept on purpose (they describe
        // what an order bought), so its images cannot be deleted either.
        Optional<Product> owner = products.findById(productId).filter(p -> !p.isDeleted());
        if (owner.isEmpty()) {
            return false;
        }
        Product product = owner.get();

        // The product id in the path is part of the identity, not decoration:
        // without this, knowing an image id would be enough to delete it from
        // any product.
        Optional<ProductImage> target = product.images().stream()
                .filter(i -> i.id().equals(imageId))
                .findFirst();
        if (target.isEmpty()) {
            return false;
        }

        String key = target.get().storageKey();
        product.removeImage(imageId);
        products.saveAndFlush(product);

        deleteObjectsAfterCommit(List.of(key));
        log.info("Deleted image id={} from product={}", imageId, productId);
        return true;
    }

    @Override
    @Transactional
    public void reclaimStorageFor(UUID productId) {
        // Read NOW, while the rows still exist. After the caller's delete
        // cascades there is no record of which objects belonged to this
        // product.
        List<String> keys = images.findStorageKeysByProduct(productId);
        if (keys.isEmpty()) {
            return;
        }
        log.debug("scheduling removal of {} objects for product={}", keys.size(), productId);
        deleteObjectsAfterCommit(keys);
    }

    /**
     * Removes objects once the database change is durable.
     *
     * <p>Registered as an after-commit callback rather than called inline,
     * because storage is not transactional: deleting the bytes inside the
     * transaction would destroy them even if the transaction then rolled back,
     * leaving a row pointing at nothing. After commit, the worst outcome is an
     * object nobody references.
     *
     * <p>A failure here is logged with the key and swallowed. Re-throwing
     * after the commit cannot undo it and would turn a successful delete into
     * a 500 for the operator; the key is recorded so an orphan can be
     * reclaimed, and because keys are content-addressed that sweep is a
     * straightforward comparison rather than a guess.
     */
    void deleteObjectsAfterCommit(List<String> keys) {
        if (keys.isEmpty()) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            keys.forEach(this::deleteQuietly);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        keys.forEach(DefaultProductImageService.this::deleteQuietly);
                    }
                });
    }

    private void deleteQuietly(String key) {
        try {
            storage.delete(key);
        } catch (RuntimeException e) {
            log.warn("Failed to delete object key={}; it is now an orphan and can be "
                    + "reclaimed by comparing storage against product_images", key, e);
        }
    }

    private static ProductImageResponse toResponse(ProductImage image) {
        return ProductImageResponse.of(
                image.id(), image.altText().orElse(null), image.width(), image.height());
    }
}

package com.oriah.catalog.storage;

import java.io.InputStream;
import java.util.Optional;

/**
 * Where product image bytes live.
 *
 * <h2>Why this is a port and not just an S3 client</h2>
 *
 * It is not speculative indirection — it has already paid for itself twice.
 * The plan for this phase specified MinIO, and MinIO's server and client
 * images turned out to have been withdrawn from Docker Hub entirely (the
 * {@code minio/} namespace still publishes twenty repositories, but
 * {@code minio/minio} and {@code minio/mc} are not among them, and an
 * authenticated pull fails identically to an anonymous one). Swapping to
 * SeaweedFS cost an endpoint property because every call goes through the S3
 * API behind this interface.
 *
 * <p>The second payoff is testing: a {@link InMemoryProductImageStorage}
 * implementation lets the ingest rules — magic bytes, dimension limits, EXIF
 * orientation, re-encoding — be tested without a container per test, while one
 * integration test exercises the real S3 implementation against a real server.
 *
 * <h2>Keys are opaque and content-addressed</h2>
 *
 * A key looks like {@code products/{productId}/{sha256}.jpg}. Callers treat it
 * as opaque: it is stored in {@code product_images.storage_key} and never
 * parsed. Nothing persists an absolute URL, so moving the read path to a CDN
 * later is a URL-builder change rather than a data migration.
 */
public interface ProductImageStorage {

    /**
     * Stores bytes under a key, overwriting if the key already exists.
     *
     * <p>Overwriting is safe precisely because keys are content-addressed:
     * the same key means the same bytes, so a re-upload is idempotent rather
     * than destructive. That is also why there is no "does it exist" check
     * before a put — the database's
     * {@code UNIQUE (product_id, content_hash)} already makes a duplicate
     * upload return the existing row without reaching storage.
     */
    void put(String key, byte[] bytes, String contentType);

    /**
     * Opens a stream for reading, or empty when the key is absent.
     *
     * <p>A stream rather than a byte array: the read path serves these to
     * browsers, and buffering every image into heap to copy it into a response
     * is how a service with a 128 MB direct-buffer ceiling falls over under
     * ordinary traffic.
     *
     * <p>Empty rather than an exception for a missing key, because the caller's
     * answer is a 404 and an absent object is an expected state — the database
     * row and the object can legitimately disagree for a moment after a
     * failed delete.
     */
    Optional<InputStream> open(String key);

    /**
     * Removes an object. Deleting an absent key is NOT an error.
     *
     * <p>Idempotent deliberately: object deletion happens after the database
     * transaction commits, because storage is not transactional. So a retry
     * can legitimately delete something already gone, and treating that as a
     * failure would turn a successful cleanup into a logged error.
     */
    void delete(String key);

    /**
     * Whether the bucket is reachable and present.
     *
     * <p>Checked at startup so a misconfigured deployment fails immediately
     * rather than on the first upload. Catalog runs with a credential scoped
     * to one bucket and CANNOT create it — that was measured, not assumed: the
     * scoped key gets 403 on a bucket create, and only the admin identity can
     * do it. So bucket creation is a separate bootstrap step and this is
     * purely a readiness assertion.
     */
    boolean bucketExists();
}

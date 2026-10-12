package com.oriah.catalog.storage;

import java.io.InputStream;
import java.util.Optional;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import lombok.extern.slf4j.Slf4j;

/**
 * Object storage over the S3 API.
 *
 * <h2>Written against the API, not against a vendor</h2>
 *
 * This phase was planned against MinIO. MinIO's server and client images turned
 * out to have been withdrawn from Docker Hub — the {@code minio/} namespace
 * still publishes twenty repositories, but {@code minio/minio} and
 * {@code minio/mc} are not among them, and an authenticated pull fails exactly
 * as an anonymous one does. Moving to SeaweedFS cost one configuration
 * property because every call here is plain S3. A vendor SDK would have cost a
 * rewrite, and this class is why that choice is now worth repeating rather than
 * regretting.
 *
 * <h2>Why the client configuration is not the SDK's defaults</h2>
 *
 * Three settings, each needed for a non-AWS server, and they are the reason
 * {@code S3ProductImageStorageIT} exists as a release gate rather than a
 * nice-to-have — a hand-rolled SigV4 probe proved the SERVER works but cannot
 * catch what the SDK sends:
 *
 * <ul>
 *   <li>{@code forcePathStyle(true)}. The SDK defaults to virtual-host
 *       addressing ({@code bucket.host/key}), which requires DNS for every
 *       bucket name. Against a container on a fixed hostname that simply does
 *       not resolve.</li>
 *   <li>{@code requestChecksumCalculation(WHEN_REQUIRED)}. Since 2.30 the SDK
 *       computes a CRC32 for every upload by default and sends it with
 *       {@code aws-chunked} transfer encoding and trailing headers. Many
 *       S3-compatible servers reject that framing outright, and the failure
 *       looks like a malformed request rather than a checksum problem.</li>
 *   <li>{@code responseChecksumValidation(WHEN_REQUIRED)}. The mirror image on
 *       the download path, for servers that do not return the checksum
 *       headers the SDK would otherwise insist on validating.</li>
 * </ul>
 */
@Slf4j
public class S3ProductImageStorage implements ProductImageStorage {

    private final S3Client client;
    private final String bucket;

    public S3ProductImageStorage(S3Client client, String bucket) {
        this.client = client;
        this.bucket = bucket;
    }

    @Override
    public void put(String key, byte[] bytes, String contentType) {
        client.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        // The NORMALISED type, derived from decoding the
                        // image. Never the client's header, which is why the
                        // read path can serve this back without sniffing.
                        .contentType(contentType)
                        .contentLength((long) bytes.length)
                        .build(),
                RequestBody.fromBytes(bytes));

        log.debug("stored object key={} bytes={} type={}", key, bytes.length, contentType);
    }

    @Override
    public Optional<InputStream> open(String key) {
        try {
            // Returns a ResponseInputStream, which streams from the socket
            // rather than buffering the object. The caller hands it to the
            // servlet response, so a large image never exists as a byte array
            // on the heap.
            return Optional.of(client.getObject(
                    GetObjectRequest.builder().bucket(bucket).key(key).build()));
        } catch (NoSuchKeyException e) {
            // Expected, not exceptional: the database row and the object can
            // legitimately disagree for a moment after a failed delete, and
            // the caller's answer is a 404.
            return Optional.empty();
        }
    }

    @Override
    public void delete(String key) {
        // S3 delete is idempotent by specification -- removing an absent key
        // succeeds. That matters because object deletion happens AFTER the
        // database transaction commits (storage is not transactional), so a
        // retry can legitimately delete something already gone.
        client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
        log.debug("deleted object key={}", key);
    }

    @Override
    public boolean bucketExists() {
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            return true;
        } catch (S3Exception e) {
            // Deliberately broad. A missing bucket is a 404, but a credential
            // scoped to one bucket can also receive 403 for a bucket it is not
            // allowed to see, and from this service's point of view those are
            // the same actionable fact: it cannot use the bucket. The status
            // is logged so an operator can tell which it was.
            log.warn("bucket '{}' is not usable: {} {}", bucket,
                    e.statusCode(), e.awsErrorDetails() == null
                            ? e.getMessage()
                            : e.awsErrorDetails().errorMessage());
            return false;
        }
    }
}

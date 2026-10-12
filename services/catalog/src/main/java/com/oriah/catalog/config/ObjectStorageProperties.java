package com.oriah.catalog.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound from the {@code oriah.catalog.storage.*} config tree.
 *
 * <p>Mutable class rather than a record, matching {@link CatalogProperties}
 * and for the same reason: Spring Cloud Context rebinds
 * {@code @ConfigurationProperties} beans in place on {@code /actuator/refresh},
 * which it cannot do to an immutable record.
 *
 * <h2>An absent endpoint is a supported configuration, not a misconfiguration</h2>
 *
 * With no {@code endpoint} set the service uses the in-memory storage
 * implementation. That is what makes {@code mvnw spring-boot:test-run} and
 * every test that is not specifically about S3 work without a container. It is
 * deliberately NOT a silent fallback in production: the docker profile sets an
 * endpoint, and if the bucket behind it is missing the service fails at
 * startup rather than quietly accepting uploads into a map that vanishes on
 * restart.
 */
@ConfigurationProperties(prefix = "oriah.catalog.storage")
public class ObjectStorageProperties {

    /**
     * S3 API endpoint, e.g. {@code http://oriah-s3:8333}.
     *
     * <p>Blank or absent selects in-memory storage.
     */
    private String endpoint;

    /** Bucket name. Created by a bootstrap step, never by this service. */
    private String bucket = "oriah-catalog";

    /**
     * Required by the SDK's signing algorithm even when the server does not
     * care about regions. SigV4 incorporates the region into the signature, so
     * client and server must simply agree on a string; this one is the
     * conventional default that S3-compatible servers accept.
     */
    private String region = "us-east-1";

    /**
     * Credentials for an account scoped to {@link #bucket}, NOT the admin
     * account.
     *
     * <p>The distinction is enforced by the server and was measured rather
     * than assumed: the scoped key receives 403 when it attempts to create a
     * bucket, and only the admin identity can. So a compromise of this service
     * cannot create or destroy buckets, and bucket creation is necessarily a
     * separate bootstrap step.
     */
    private String accessKey;

    private String secretKey;

    public String getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(String endpoint) {
        this.endpoint = endpoint;
    }

    public String getBucket() {
        return bucket;
    }

    public void setBucket(String bucket) {
        this.bucket = bucket;
    }

    public String getRegion() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public void setAccessKey(String accessKey) {
        this.accessKey = accessKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey;
    }

    /** True when a real object store is configured. */
    public boolean isConfigured() {
        return endpoint != null && !endpoint.isBlank();
    }
}

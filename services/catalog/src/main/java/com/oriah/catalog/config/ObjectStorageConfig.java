package com.oriah.catalog.config;

import java.net.URI;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import com.oriah.catalog.storage.InMemoryProductImageStorage;
import com.oriah.catalog.storage.ProductImageStorage;
import com.oriah.catalog.storage.S3ProductImageStorage;

import lombok.extern.slf4j.Slf4j;

/**
 * Chooses and configures where product image bytes live.
 *
 * <h2>Two implementations, one chosen by configuration</h2>
 *
 * With {@code oriah.catalog.storage.endpoint} set, the S3 implementation. With
 * it absent, the in-memory one — which is what makes {@code spring-boot:test-run}
 * and every non-S3 test work without a container, and is a supported
 * configuration rather than a fallback from failure.
 *
 * <p>When an endpoint IS configured, a missing bucket fails the application
 * context. Accepting uploads into storage that is not there would answer 201
 * to an admin and lose the file, and the product row would then reference an
 * object that never existed.
 */
@Configuration(proxyBeanMethods = false)
@Slf4j
public class ObjectStorageConfig {

    @Bean
    ProductImageStorage productImageStorage(ObjectStorageProperties properties) {
        if (!properties.isConfigured()) {
            log.info("No object storage endpoint configured; product images are held in memory "
                    + "and will not survive a restart");
            return new InMemoryProductImageStorage();
        }

        var storage = new S3ProductImageStorage(
                s3Client(properties), properties.getBucket());

        if (!storage.bucketExists()) {
            // Fails the context deliberately. This service runs with a
            // credential scoped to one bucket and CANNOT create it -- measured,
            // not assumed: the scoped key gets 403 on a bucket create and only
            // the admin identity succeeds. So there is nothing useful to do
            // here except refuse to start, and a bad deploy then never passes
            // its readiness probe.
            throw new IllegalStateException(
                    "object storage bucket '" + properties.getBucket() + "' is not usable at "
                            + properties.getEndpoint() + ". It is created by the storage "
                            + "bootstrap step using the ADMIN credentials; this service's "
                            + "credentials are scoped to the bucket and cannot create it.");
        }

        log.info("Product images stored in bucket '{}' at {}",
                properties.getBucket(), properties.getEndpoint());
        return storage;
    }

    /**
     * The S3 client, with the three settings a non-AWS server needs.
     *
     * <p>Each is explained in {@link S3ProductImageStorage}'s class javadoc.
     * The short version: path-style addressing because per-bucket DNS does not
     * exist for a container, and both checksum settings relaxed to
     * {@code WHEN_REQUIRED} because the SDK's default CRC32-with-aws-chunked
     * framing is rejected by many S3-compatible servers in a way that looks
     * like a malformed request rather than a checksum problem.
     */
    private static S3Client s3Client(ObjectStorageProperties properties) {
        return S3Client.builder()
                .endpointOverride(URI.create(properties.getEndpoint()))
                .region(Region.of(properties.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(
                                properties.getAccessKey(), properties.getSecretKey())))
                .forcePathStyle(true)
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                // Blocking HTTP, matching this service's MVC model. Explicitly
                // not netty-nio-client: an async stack buys nothing here, and
                // the gateway's experience with Netty direct buffers is a good
                // reason not to introduce a second Netty into a service that
                // has none.
                .httpClient(UrlConnectionHttpClient.builder().build())
                .build();
    }
}

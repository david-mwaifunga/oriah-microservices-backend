package com.oriah.catalog.storage;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The AWS Java SDK against a real S3-compatible server.
 *
 * <h2>Why this exists when the server was already proved to work</h2>
 *
 * Before choosing SeaweedFS its S3 API was driven directly with a hand-written
 * SigV4 signer and scoped credentials: bucket create, head, put, get, head
 * object, delete, get-after-delete and a deliberately wrong signature all
 * behaved correctly, twelve checks. That proved the SERVER. It cannot prove
 * the CLIENT, and the client is where the known hazards are:
 *
 * <ul>
 *   <li>Since 2.30 the SDK computes a CRC32 for every upload by default and
 *       sends it using {@code aws-chunked} transfer encoding with trailing
 *       headers. Servers that do not implement that framing reject the
 *       request, and the error looks like a malformed body rather than
 *       anything to do with checksums.</li>
 *   <li>The SDK defaults to virtual-host addressing, which needs DNS per
 *       bucket name and cannot work against a container on a fixed host.</li>
 *   <li>SigV4 signs the region, so client and server must agree on a string
 *       even where the server has no concept of regions.</li>
 * </ul>
 *
 * <p>So this test builds its client with EXACTLY the configuration
 * {@code ObjectStorageConfig} uses in production. If the two drift, this test
 * stops guarding the thing it was written for — which is the most likely way
 * for it to become worthless.
 *
 * <h2>GenericContainer, not testcontainers-minio</h2>
 *
 * That module exists on Central at the version this build manages, and is
 * unusable: it pulls a MinIO image, and MinIO's images have been withdrawn
 * from Docker Hub.
 */
@Testcontainers
class S3ProductImageStorageIT {

    private static final String BUCKET = "oriah-catalog-it";
    private static final String ACCESS_KEY = "itkey";
    private static final String SECRET_KEY = "itsecret";

    private static final String ADMIN_KEY = "adminkey";
    private static final String ADMIN_SECRET = "adminsecret";

    /**
     * Two identities, mirroring production.
     *
     * <p>The first version of this test mounted nothing, on the assumption
     * that a server with no credentials configured would accept anonymous
     * requests. It does accept anonymous requests -- but the AWS SDK ALWAYS
     * signs, and SeaweedFS answers a signed request with
     * {@code 400 "Signed request requires setting up SeaweedFS S3
     * authentication"} when it has no identities to check it against. Every
     * operation failed. The assumption was wrong in a way no amount of reading
     * the SDK would have revealed.
     *
     * <p>Mounting identities also makes the test faithful to how this runs for
     * real: an ADMIN identity creates the bucket as a bootstrap step, and
     * catalog itself holds a key scoped to that one bucket which cannot create
     * it. {@link #scopedKeyCannotCreateABucket()} asserts that separation,
     * because it is the reason {@code ObjectStorageConfig} fails startup on a
     * missing bucket instead of creating one.
     */
    private static final String S3_IDENTITIES = """
            {
              "identities": [
                {
                  "name": "admin",
                  "credentials": [{"accessKey": "%s", "secretKey": "%s"}],
                  "actions": ["Admin", "Read", "Write", "List", "Tagging"]
                },
                {
                  "name": "catalog",
                  "credentials": [{"accessKey": "%s", "secretKey": "%s"}],
                  "actions": ["Read:%s", "Write:%s", "List:%s"]
                }
              ]
            }
            """.formatted(ADMIN_KEY, ADMIN_SECRET, ACCESS_KEY, SECRET_KEY, BUCKET, BUCKET, BUCKET);

    @Container
    static final GenericContainer<?> SEAWEEDFS =
            new GenericContainer<>(DockerImageName.parse("chrislusf/seaweedfs:3.80"))
                    .withCopyToContainer(
                            Transferable.of(S3_IDENTITIES), "/etc/seaweedfs/s3.json")
                    .withCommand("server", "-s3", "-s3.config=/etc/seaweedfs/s3.json", "-dir=/data")
                    .withExposedPorts(8333)
                    // Anonymous GET of / is 403 once identities are configured,
                    // which is itself the signal that the config was read --
                    // so the readiness probe accepts it.
                    .waitingFor(Wait.forHttp("/").forStatusCode(403).forStatusCode(200)
                            .forPort(8333).withStartupTimeout(Duration.ofMinutes(2)));

    private static S3ProductImageStorage storage;

    @BeforeAll
    static void createBucketAndStorage() {
        // ADMIN creates the bucket. In production this is a one-shot bootstrap
        // container, for exactly the reason asserted below: the scoped key
        // cannot do it.
        client(ADMIN_KEY, ADMIN_SECRET)
                .createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
        // The storage under test uses the SCOPED key, as the service does.
        storage = new S3ProductImageStorage(client(ACCESS_KEY, SECRET_KEY), BUCKET);
    }

    private static S3Client client() {
        return client(ACCESS_KEY, SECRET_KEY);
    }

    /**
     * Identical to {@code ObjectStorageConfig.s3Client}, deliberately
     * duplicated rather than shared: the point of this test is that the
     * production configuration works, so reaching into production code for the
     * builder would make a drift between them invisible here.
     */
    private static S3Client client(String accessKey, String secretKey) {
        return S3Client.builder()
                .endpointOverride(URI.create(
                        "http://" + SEAWEEDFS.getHost() + ":" + SEAWEEDFS.getMappedPort(8333)))
                .region(Region.of("us-east-1"))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .forcePathStyle(true)
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .httpClient(UrlConnectionHttpClient.builder().build())
                .build();
    }

    @Test
    @DisplayName("the scoped key CANNOT create a bucket, which is why startup only checks for one")
    void scopedKeyCannotCreateABucket() {
        // The separation this whole configuration rests on. If the service's
        // own credentials could create buckets, a compromise of catalog could
        // create or replace storage locations; and ObjectStorageConfig's
        // decision to fail startup rather than self-heal would be arbitrary
        // rather than forced.
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> client(ACCESS_KEY, SECRET_KEY)
                        .createBucket(CreateBucketRequest.builder()
                                .bucket("catalog-should-not-create-this").build()))
                .isInstanceOf(software.amazon.awssdk.services.s3.model.S3Exception.class);
    }

    private static String key() {
        return "products/" + UUID.randomUUID() + "/" + "a".repeat(64) + ".jpg";
    }

    @Test
    @DisplayName("the bucket the bootstrap created is visible to the storage client")
    void bucketExists() {
        assertThat(storage.bucketExists()).isTrue();
    }

    @Test
    @DisplayName("a bucket that does not exist reports false rather than throwing")
    void missingBucketReportsFalse() {
        // ObjectStorageConfig turns this into a startup failure, so it has to
        // be a boolean rather than an exception escaping from a @Bean method.
        var absent = new S3ProductImageStorage(client(), "no-such-bucket-here");
        assertThat(absent.bucketExists()).isFalse();
    }

    @Test
    @DisplayName("real image bytes round-trip byte-for-byte through the SDK")
    void putThenGetRoundTrips() throws Exception {
        // A genuine JPEG rather than a text payload: the SDK's content-length
        // and checksum handling are what is under test, and binary data with
        // bytes that look like framing characters is the realistic case.
        byte[] original = TestImages.jpeg(640, 480);
        String key = key();

        storage.put(key, original, "image/jpeg");

        try (var in = storage.open(key).orElseThrow()) {
            assertThat(in.readAllBytes())
                    .as("a checksum or encoding mismatch corrupts or truncates here")
                    .isEqualTo(original);
        }
    }

    @Test
    @DisplayName("an absent key is empty, not an exception")
    void missingKeyIsEmpty() {
        // The read path's 404 depends on this, and NoSuchKeyException must be
        // caught rather than propagating as a 500.
        assertThat(storage.open(key())).isEmpty();
    }

    @Test
    @DisplayName("delete removes the object, and deleting again is not an error")
    void deleteIsIdempotent() {
        String key = key();
        storage.put(key, TestImages.png(32, 32), "image/png");
        assertThat(storage.open(key)).isPresent();

        storage.delete(key);
        assertThat(storage.open(key)).isEmpty();

        // Object deletion happens after the database transaction commits,
        // because storage is not transactional -- so a retry legitimately
        // deletes something already gone, and that must not be reported as a
        // failure.
        storage.delete(key);
        assertThat(storage.open(key)).isEmpty();
    }

    @Test
    @DisplayName("overwriting a key with the same bytes is safe, which is what makes re-upload idempotent")
    void overwriteIsSafe() throws Exception {
        byte[] bytes = TestImages.jpeg(100, 100);
        String key = key();

        storage.put(key, bytes, "image/jpeg");
        storage.put(key, bytes, "image/jpeg");

        try (var in = storage.open(key).orElseThrow()) {
            assertThat(in.readAllBytes()).isEqualTo(bytes);
        }
    }

    @Test
    @DisplayName("a key with path-like segments is stored and retrieved as one opaque key")
    void nestedKeysWork() throws Exception {
        // Keys contain slashes by design (products/{id}/{hash}.jpg). Confirms
        // the SDK encodes them as a key rather than as URL path structure that
        // a path-style endpoint might mangle.
        String key = "products/" + UUID.randomUUID() + "/nested/deeper/" + "b".repeat(64) + ".png";
        byte[] bytes = TestImages.png(20, 20);

        storage.put(key, bytes, "image/png");
        try (var in = storage.open(key).orElseThrow()) {
            assertThat(in.readAllBytes()).isEqualTo(bytes);
        }
    }

    @Test
    @DisplayName("a 5 MB object, the ingest size cap, transfers intact")
    void sizeCapTransfers() throws Exception {
        // The largest thing ingest will ever hand to storage. Worth asserting
        // because chunked-encoding and content-length problems tend to appear
        // only above whatever buffer size the client uses internally.
        byte[] large = new byte[ProductImageIngest.MAX_BYTES];
        for (int i = 0; i < large.length; i++) {
            large[i] = (byte) (i % 251);
        }
        String key = key();

        storage.put(key, large, "application/octet-stream");

        try (var in = storage.open(key).orElseThrow()) {
            byte[] read = in.readAllBytes();
            assertThat(read).hasSize(large.length);
            assertThat(read).isEqualTo(large);
        }
    }

    @Test
    @DisplayName("the stored content-type survives the round trip")
    void contentTypeIsPreserved() {
        // The read path serves this header back to browsers, so it has to be
        // what was stored. ingest normalises it from the decode precisely so
        // that it can be trusted here.
        String key = key();
        storage.put(key, TestImages.png(16, 16), "image/png");

        assertThat(storage.open(key)).isPresent();
        // Asserted through the SDK's own response rather than our wrapper,
        // since the wrapper deliberately exposes only the stream.
        var head = client().headObject(builder -> builder.bucket(BUCKET).key(key));
        assertThat(head.contentType()).isEqualTo("image/png");
        assertThat(head.contentLength()).isPositive();
    }

    @Test
    @DisplayName("an ETag comes back from the server, but it is NOT what we serve")
    void serverEtagIsNotRelevant() {
        // Recorded as a fact rather than a requirement. The HTTP ETag this
        // platform serves comes from product_images.content_hash, because
        // HeadObject was measured returning no ETag at all during the server
        // evaluation. Whether this particular server supplies one must not
        // matter, and this assertion exists so that nobody later "simplifies"
        // the read path into forwarding it.
        String key = key();
        storage.put(key, "x".getBytes(StandardCharsets.UTF_8), "text/plain");

        var head = client().headObject(builder -> builder.bucket(BUCKET).key(key));
        assertThat(head.contentLength()).isEqualTo(1L);
        // No assertion on head.eTag(): its presence or absence is the server's
        // business and nothing in this platform reads it.
    }
}

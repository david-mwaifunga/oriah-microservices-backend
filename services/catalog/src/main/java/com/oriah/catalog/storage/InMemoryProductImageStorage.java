package com.oriah.catalog.storage;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps image bytes in a map.
 *
 * <h2>This is production code, not a test fixture, and the distinction matters</h2>
 *
 * It lives in {@code src/main} because it is the active implementation when no
 * object-storage endpoint is configured — which is how the service runs under
 * {@code mvnw spring-boot:test-run} and in every test that is not specifically
 * about S3. The alternative, a Mockito mock of {@link ProductImageStorage},
 * would let the ingest tests pass while storing nothing: a mock returns null
 * from {@code open} and the tests asserting that re-encoded bytes come back
 * intact would be asserting against a stub.
 *
 * <h2>What it deliberately does NOT emulate</h2>
 *
 * Durability, atomicity under concurrent writes, and size limits. It is not a
 * stand-in for the real thing under load, and the one integration test that
 * drives the actual S3 implementation against a real server exists because
 * this class cannot substitute for it — SigV4 signing, path-style addressing
 * and the SDK's request-checksum behaviour are exactly the things a map
 * cannot get wrong.
 */
public class InMemoryProductImageStorage implements ProductImageStorage {

    private record StoredObject(byte[] bytes, String contentType) {
    }

    private final Map<String, StoredObject> objects = new ConcurrentHashMap<>();

    @Override
    public void put(String key, byte[] bytes, String contentType) {
        // Copied rather than referenced: the caller owns the array it passed
        // and may reuse its buffer, and an aliased write would mutate stored
        // bytes whose hash is already recorded in the database.
        objects.put(key, new StoredObject(bytes.clone(), contentType));
    }

    @Override
    public Optional<InputStream> open(String key) {
        return Optional.ofNullable(objects.get(key))
                .map(stored -> new ByteArrayInputStream(stored.bytes()));
    }

    @Override
    public void delete(String key) {
        objects.remove(key);
    }

    /** Always true: there is no bucket to be missing. */
    @Override
    public boolean bucketExists() {
        return true;
    }

    // ---------------------------------------------------------------- testing

    /** Number of stored objects, for tests asserting that cleanup happened. */
    public int size() {
        return objects.size();
    }

    public Optional<String> contentTypeOf(String key) {
        return Optional.ofNullable(objects.get(key)).map(StoredObject::contentType);
    }

    public boolean contains(String key) {
        return objects.containsKey(key);
    }

    public void clear() {
        objects.clear();
    }
}

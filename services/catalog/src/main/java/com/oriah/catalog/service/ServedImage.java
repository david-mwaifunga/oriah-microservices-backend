package com.oriah.catalog.service;

import java.io.InputStream;

/**
 * An image ready to be written to an HTTP response.
 *
 * <p>Carries a STREAM rather than a byte array. The read path is the
 * highest-volume endpoint in the catalogue, and buffering each image into heap
 * to copy it into a response is how a service falls over under ordinary
 * traffic rather than under load.
 *
 * @param contentHash the value served as the HTTP {@code ETag}. It comes from
 *                    the database column, NOT from the object store -- during
 *                    the storage evaluation {@code HeadObject} was measured
 *                    returning no ETag at all, so there is nothing to forward
 *                    even where a server does supply one.
 */
public record ServedImage(
        InputStream content,
        String contentType,
        long byteSize,
        String contentHash) {
}

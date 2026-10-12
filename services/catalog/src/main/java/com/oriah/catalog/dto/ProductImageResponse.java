package com.oriah.catalog.dto;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One image, as a reference rather than as bytes.
 *
 * <p>{@code url} is RELATIVE ({@code /api/v1/product-images/{id}}) and that is
 * deliberate rather than incidental. The browser reaches this API same-origin
 * through the frontend's rewrite, and Next classifies a non-absolute image
 * {@code src} as internal, resolving it in-process without touching DNS. An
 * absolute URL would have to name a host, which differs between the browser,
 * the server-rendering container and any other environment -- and would have
 * to be persisted, making an environment detail part of the data.
 *
 * <p>{@code width} and {@code height} are published so the client can reserve
 * the right space before the bytes arrive, instead of reflowing the grid as
 * each image loads.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProductImageResponse(
        UUID id,
        String url,
        String altText,
        int width,
        int height
) {
    public static ProductImageResponse of(UUID id, String altText, int width, int height) {
        return new ProductImageResponse(id, "/api/v1/product-images/" + id, altText, width, height);
    }
}

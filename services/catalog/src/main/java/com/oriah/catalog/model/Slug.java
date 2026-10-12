package com.oriah.catalog.model;

/**
 * Turns a product or category title into a URL slug.
 *
 * <h2>This has to agree with SQL, and that is the whole design constraint</h2>
 *
 * {@code V3__product_types_variants_categories_images.sql} backfilled
 * {@code products.slug} for every pre-existing product with
 *
 * <pre>trim(both '-' from regexp_replace(lower(title), '[^a-z0-9]+', '-', 'g'))</pre>
 *
 * and this class produces slugs for everything created afterwards. The column
 * is {@code NOT NULL UNIQUE}, so a drift between the two does not fail
 * loudly — it produces a different URL for the same title depending on which
 * side of the migration the product was created, and surfaces only if two
 * slugs happen to collide. {@code SlugTest} therefore asserts the regex's
 * behaviour rather than this implementation's.
 *
 * <p>Non-ASCII characters are not in {@code [a-z0-9]}, so they separate:
 * "Rosé Gold" becomes {@code ros-gold}. That is what the SQL does, and a
 * readable approximation beats a URL of percent-escapes. If transliteration
 * is ever wanted ({@code rose-gold}), it has to change on both sides at once,
 * with a migration to re-slug existing rows.
 */
public final class Slug {

    /**
     * Used when a title strips away to nothing — a title of pure punctuation
     * or of entirely non-Latin characters. The column is {@code NOT NULL
     * UNIQUE}, so an empty slug would be storable exactly once and collide
     * forever after. The service appends a discriminator when this base
     * collides, the same way the migration appended part of the id.
     */
    private static final String FALLBACK = "product";

    private Slug() {
    }

    /**
     * @throws IllegalArgumentException if the title is null or blank — a
     *         product cannot have one of those anyway, and failing here is
     *         clearer than emitting the fallback for what is really a bad
     *         title
     */
    public static String from(String title) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        String slug = title.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+", "")
                .replaceAll("-+$", "");
        return slug.isEmpty() ? FALLBACK : slug;
    }
}

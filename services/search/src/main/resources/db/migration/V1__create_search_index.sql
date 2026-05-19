-- Denormalized search index. One row per product (variantId), with a
-- generated tsvector column over title + description + tags for
-- Postgres full-text search.
--
-- Maintained by ProductEventConsumer (subscribes to catalog.product
-- Kafka topic). Eventually consistent — there's a small window where
-- a search returns stale data after a catalog edit, until the consumer
-- catches up. Real-time consistency requires synchronous fan-out which
-- breaks the catalog->search service boundary.

CREATE TABLE search_index (
    variant_id     UUID         PRIMARY KEY,
    title          VARCHAR(255) NOT NULL,
    description    TEXT,
    tags           TEXT,         -- space-joined tags so the tsvector picks them up
    document       tsvector
        GENERATED ALWAYS AS (
            setweight(to_tsvector('english', coalesce(title,'')), 'A')
            || setweight(to_tsvector('english', coalesce(description,'')), 'B')
            || setweight(to_tsvector('english', coalesce(tags,'')),         'C')
        ) STORED,
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- GIN index over the tsvector — required for fast `@@` matching.
CREATE INDEX idx_search_document ON search_index USING gin(document);

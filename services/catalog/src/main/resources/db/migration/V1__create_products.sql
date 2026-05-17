CREATE TABLE products (
    id          UUID PRIMARY KEY,
    title       TEXT NOT NULL,
    description TEXT
);

CREATE TABLE product_tags (
    product_id UUID NOT NULL REFERENCES products(id) ON DELETE CASCADE,
    tag        TEXT NOT NULL
);

CREATE INDEX idx_product_tags_product_id ON product_tags(product_id);

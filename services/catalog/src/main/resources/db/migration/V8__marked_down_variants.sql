-- Phase 12: a returned piece that is sold again at a lower price gets a listing of its own.
--
-- It is a variant of the same product, in condition MARKED_DOWN, made for ONE returned piece. It copies the options of the variant
-- the piece was sold as, so "one variant per option combination" now binds only the ordinary (NEW) variants.

ALTER TABLE product_variants ADD COLUMN condition VARCHAR(12) NOT NULL DEFAULT 'NEW';
-- Checked at commit, not row by row: a variant is never deleted in production, but whatever removes a product's variants together
-- (a clean-up, a test) must not depend on the order the rows go in.
ALTER TABLE product_variants ADD COLUMN source_variant_id UUID REFERENCES product_variants (id) DEFERRABLE INITIALLY DEFERRED;
-- The returned piece it was made for (the order system's id for the piece).
ALTER TABLE product_variants ADD COLUMN markdown_piece_id UUID;
ALTER TABLE product_variants ADD CONSTRAINT product_variants_condition_known CHECK (condition IN ('NEW', 'MARKED_DOWN'));
-- A marked-down variant names where it came from and the piece it is for; an ordinary one names neither.
-- (condition is NOT NULL and "x IS NOT NULL" is never NULL, so no arm of this can pass on a NULL.)
ALTER TABLE product_variants ADD CONSTRAINT product_variants_marked_down_whole CHECK (
        (condition = 'MARKED_DOWN') = (source_variant_id IS NOT NULL)
    AND (condition = 'MARKED_DOWN') = (markdown_piece_id IS NOT NULL)
    AND (source_variant_id IS NULL OR source_variant_id <> id));
ALTER TABLE product_variants DROP CONSTRAINT product_variants_option_unique;
CREATE UNIQUE INDEX product_variants_option_unique ON product_variants (product_id, option_digest) WHERE condition = 'NEW';
-- One listing per returned piece.
CREATE UNIQUE INDEX product_variants_markdown_piece ON product_variants (markdown_piece_id) WHERE markdown_piece_id IS NOT NULL;

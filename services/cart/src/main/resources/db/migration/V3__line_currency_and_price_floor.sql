-- ---------------------------------------------------------------------------
-- A cart line gains a currency, a real price floor, and a title column big
-- enough for the label the server now writes into it.
--
-- Three changes, one migration, because all three are consequences of the same
-- decision: the price on a cart line stops coming from the shopper and starts
-- coming from catalog.
--
-- 1. CURRENCY. There was none anywhere in cart -- not in V1, not in V2, not on
--    Cart or CartItem -- while catalog prices every variant in a currency and
--    the brand prices in Kwacha. Without it a bag whose prices are stored
--    cannot say what they are denominated in, so a degraded (catalog
--    unreachable) response cannot report one and nothing downstream can derive
--    a payment currency from the cart.
--
--    VARCHAR(3) NOT NULL DEFAULT 'ZMW' mirrors catalog's
--    product_variants.currency exactly, including the default. The default's
--    real job is this migration: it backfills pre-existing rows, which were
--    written when the only currency in the platform was an unlabelled USD
--    literal. That is a lie for those rows either way -- there is no honest
--    value, because nobody recorded one -- and ZMW is at least the brand's own
--    unit. Carts are short-lived and rebuildable, which is why this is
--    acceptable here and would not be on order_items.
--
-- 2. THE PRICE FLOOR. V1 wrote CHECK (unit_price >= 0), which admits 0.00.
--    Zero is not a free item; it is catalog's "nobody has priced this yet"
--    marker for a draft variant (ProductVariant permits 0.00 deliberately, and
--    the publish rule is what refuses to put such a product on the storefront).
--    A zero-priced cart line is therefore unsellable, and the aggregate now
--    refuses to create one. The constraint is tightened so the database refuses
--    it too: a money column deserves both guards, because the Java one gives a
--    readable error and this one survives a future code path that forgets.
--
--    Any pre-existing 0.00 rows are DELETED rather than repriced. There is no
--    value to reprice them to -- this migration cannot call catalog -- and a
--    line that cannot be bought is worth less to the shopper than a bag that
--    loads. The alternative, leaving the constraint loose, keeps the hole open
--    in the one place that outlives every code change.
--
--    The constraint is dropped BY ITS GENERATED NAME, verified against the
--    running database (SELECT conname FROM pg_constraint ... ->
--    cart_items_unit_price_check), because V1 declared it inline and unnamed.
--    Deliberately not DROP CONSTRAINT IF EXISTS: if the name differs in some
--    environment, this migration must stop and say so, not quietly skip the
--    drop and leave the loose constraint in place while reporting success.
--
-- 3. THE TITLE COLUMN. VARCHAR(255) -> TEXT. This one prevents a 500 that this
--    phase would otherwise have INTRODUCED. The line title stops being the
--    shopper's string and becomes catalog's composed variantTitle: a product
--    title of up to 200 characters, plus ' - ', plus one 'name value' pair per
--    option axis at up to 64 characters each. A single option axis already
--    reaches roughly 332 characters. The old column would have turned the
--    server's own label into a DataIntegrityViolationException rendered as a
--    500 -- the same defect the 256-char client title used to cause, relocated
--    to somewhere no client could trigger it or avoid it.
--
--    TEXT matches products.title in catalog, which is TEXT against a plain
--    String field and passes ddl-auto=validate today. varchar -> text is
--    binary-coercible in Postgres, so this does not rewrite the table.
-- ---------------------------------------------------------------------------

ALTER TABLE cart_items
    ADD COLUMN currency VARCHAR(3) NOT NULL DEFAULT 'ZMW';

ALTER TABLE cart_items
    ALTER COLUMN title TYPE TEXT;

DELETE FROM cart_items WHERE unit_price = 0;

ALTER TABLE cart_items
    DROP CONSTRAINT cart_items_unit_price_check;

ALTER TABLE cart_items
    ADD CONSTRAINT cart_items_unit_price_check CHECK (unit_price > 0);

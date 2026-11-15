package com.oriah.catalog.constants;

/**
 * Catalog's own constants — the course's {@code constants/XxxConstants}
 * convention.
 *
 * <h2>What is here, and what deliberately is not</h2>
 *
 * <p>Only values referenced from more than one place in <strong>main</strong>
 * code. A constant used once is decorative: it moves a literal one file away
 * from where it matters and buys nothing, while making the reader follow a
 * reference to find out what a path is.
 *
 * <p>So the course's {@code STATUS_201} / {@code MESSAGE_200} style is not
 * reproduced. Those exist there to fill a {@code ResponseDto} envelope, and
 * this project returns the created resource with a {@code Location} header
 * instead — a recorded divergence. Response <em>statuses</em> live in
 * {@code @ApiResponse} annotations next to the handlers that produce them,
 * which is where a reader looks for them.
 *
 * <h2>Why the tests do not use the path constants</h2>
 *
 * <p>This is the non-obvious part. The tests keep their URLs as literals on
 * purpose. If {@code ProductController} and {@code ProductControllerTest} both
 * read {@code PUBLIC_BASE}, then editing that constant edits both and the test
 * still passes — it stops being a guard on the published path and becomes a
 * guard on nothing. A test asserting a URL should state the URL, so that the
 * assertion is an <em>independent</em> statement of the contract.
 *
 * <p>{@link #ROLE_CATALOG_ADMIN} is different and is shared with the tests,
 * because there the independent source of truth is elsewhere entirely: the role
 * must exist in {@code keycloak/import/oriah-realm.json}, and
 * {@code scripts/validate_roles.py} asserts exactly that across all 12
 * services. A typo here fails that guard rather than silently passing, so the
 * sharing costs nothing.
 */
public final class CatalogConstants {

    private CatalogConstants() {
        // Constants only.
    }

    /** Public, anonymous storefront reads. */
    public static final String PUBLIC_BASE = "/api/v1/products";

    /** Admin writes, behind {@link #ROLE_CATALOG_ADMIN}. */
    public static final String ADMIN_BASE = "/api/v1/admin/products";

    /**
     * The realm role guarding every catalog write.
     *
     * <p>Usable directly inside {@code @PreAuthorize} because concatenation of
     * {@code static final String} literals is a compile-time constant
     * expression, which is what an annotation value requires:
     *
     * <pre>{@code
     * @PreAuthorize("hasRole('" + CatalogConstants.ROLE_CATALOG_ADMIN + "')")
     * }</pre>
     *
     * <p>Note this is <strong>also</strong> the role on
     * {@code /api/v1/admin/promotions/**}, which is not derivable from that
     * path and is why a convention mapping {@code /admin/<svc>/** ->
     * <SVC>_ADMIN} would be wrong — no {@code PROMOTION_ADMIN} role exists.
     * Recorded in the Tier 3 findings.
     */
    public static final String ROLE_CATALOG_ADMIN = "CATALOG_ADMIN";

    /**
     * Procurement's capability, which may also resolve a variant it is about to
     * order ({@code VariantLookupController}, internal: not routed by the gateway).
     * Defined in the realm like every capability; validate_roles.py checks it.
     */
    public static final String ROLE_PROCUREMENT_ADMIN = "PROCUREMENT_ADMIN";

    /**
     * A packer or driver (Phase 8), who may resolve the name of a box so a packing slip can say which box goes out ({@code VariantLookupController}:
     * identity only, never a price). Defined in the realm like every capability; validate_roles.py checks it.
     */
    public static final String ROLE_FULFILMENT_AGENT = "FULFILMENT_AGENT";

    /**
     * Sales staff (Phase 9), who record a sale by phone and choose its box: they may read the as-of price of a piece and the list of boxes
     * ({@code PackagingListController}: identity only, never a cost). Defined in the realm like every capability.
     */
    public static final String ROLE_SALES_AGENT = "SALES_AGENT";

    /**
     * Reading what stock cost. The pricing screen shows landed cost, so everything under {@link #PRICING_BASE} needs it as well
     * as {@link #ROLE_CATALOG_ADMIN}: cost is gated at the API, never by hiding a column in a screen. Defined in the realm like
     * every capability; validate_roles.py checks it.
     */
    public static final String ROLE_COST_VIEWER = "COST_VIEWER";

    /** Setting prices and reading what they were compared with; CATALOG_ADMIN and COST_VIEWER both. */
    public static final String PRICING_BASE = "/api/v1/admin/pricing";

    /** Default page size for the public product list. */
    public static final int DEFAULT_PAGE_SIZE = 20;

    /**
     * The most rows one admin list page returns, however many are asked for. Each
     * row carries its variants, so an unbounded page is an unbounded read.
     */
    public static final int ADMIN_MAX_PAGE_SIZE = 100;
}

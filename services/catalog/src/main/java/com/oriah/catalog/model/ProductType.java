package com.oriah.catalog.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A product type row: the database half of the type system.
 *
 * <p>The ROWS live here so {@code products.product_type_code} has a real
 * foreign key and the admin dropdown has something to enumerate. The
 * ATTRIBUTE DEFINITIONS live in {@link ProductTypeRegistry}, because they must
 * drive server-side validation, the admin form AND the PDP spec table, and a
 * second definition in the database would be a second source of truth with no
 * compile-time link to the first.
 *
 * <p>The two halves are tied together by a startup check: a row whose code the
 * registry does not know fails the application context. Without it, a type
 * could exist that validates its products against no rules at all.
 *
 * <p>Seeded by {@code V3} with JEWELLERY and WATCH. There is no admin write
 * path -- adding a type means adding its definitions in Java, so it is a
 * deploy, not a form submission.
 */
@Entity
@Table(name = "product_types")
public class ProductType {

    @Id
    @Column(nullable = false)
    private String code;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(nullable = false)
    private int position;

    protected ProductType() {
        // For JPA hydration only.
    }

    public ProductType(String code, String displayName, int position) {
        this.code = code;
        this.displayName = displayName;
        this.position = position;
    }

    public String code() {
        return code;
    }

    public String displayName() {
        return displayName;
    }

    public int position() {
        return position;
    }
}

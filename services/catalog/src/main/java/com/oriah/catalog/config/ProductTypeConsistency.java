package com.oriah.catalog.config;

import java.util.Set;
import java.util.TreeSet;

/**
 * Checks the two halves of the product-type system against each other.
 *
 * <p>A type's CODE is a row in {@code product_types}, so
 * {@code products.product_type_code} has a real foreign key and the admin
 * dropdown has something to enumerate. Its ATTRIBUTE DEFINITIONS live in
 * {@link com.oriah.catalog.model.ProductTypeRegistry}, so that adding a spec
 * field is one edit which validation, the admin form and the PDP spec table
 * all pick up.
 *
 * <p>That split is only safe if the halves cannot drift, and both directions
 * of drift are real bugs with different causes:
 *
 * <ul>
 *   <li>A row the registry does not know is the dangerous one: products of
 *       that type would be validated against an empty definition list, which
 *       is an attribute allow-list that allows everything.</li>
 *   <li>A registry code with no row means creating such a product violates
 *       the foreign key, so the symptom is a constraint violation on save
 *       rather than the missing seed migration it really is.</li>
 * </ul>
 *
 * <p>Verified once at startup, by {@link ProductTypeConsistencyVerifier},
 * rather than per request: the right response to either condition is "this
 * build must not serve traffic", and a per-request check would turn a
 * deployment mistake into an intermittent 500.
 */
public final class ProductTypeConsistency {

    private ProductTypeConsistency() {
    }

    /**
     * @param databaseCodes codes present in {@code product_types}
     * @param registryCodes codes the running build knows definitions for
     * @throws IllegalStateException if either side holds a code the other
     *         does not, reporting both directions together so one restart
     *         surfaces the whole problem
     */
    public static void verify(Set<String> databaseCodes, Set<String> registryCodes) {
        var unknownToRegistry = new TreeSet<>(databaseCodes);
        unknownToRegistry.removeAll(registryCodes);

        var missingFromDatabase = new TreeSet<>(registryCodes);
        missingFromDatabase.removeAll(databaseCodes);

        if (unknownToRegistry.isEmpty() && missingFromDatabase.isEmpty()) {
            return;
        }

        var message = new StringBuilder("product_types is out of step with ProductTypeRegistry.");
        if (!unknownToRegistry.isEmpty()) {
            message.append(" Rows with no attribute definitions in this build: ")
                    .append(unknownToRegistry)
                    .append(" (products of those types would validate against no rules);");
        }
        if (!missingFromDatabase.isEmpty()) {
            message.append(" Registry types with no row: ")
                    .append(missingFromDatabase)
                    .append(" (a product of those types would violate the foreign key)");
        }
        message.append(". Add the missing Flyway seed or the missing registry definitions.");
        throw new IllegalStateException(message.toString());
    }
}

package com.oriah.catalog.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The single definition of what each product type's spec fields are.
 *
 * <h2>Why these live in Java and the type rows live in the database</h2>
 *
 * Three consumers need this information and must never disagree: server-side
 * validation, the admin form (which renders a control per field), and the PDP
 * spec table. Putting the definitions in a table would give two sources of
 * truth with no compile-time link between them, plus an attribute-definition
 * admin screen nobody asked for. Putting them here means adding a field is one
 * edit that the validator, the form and the storefront all pick up.
 *
 * <p>What the database does own is the set of type CODES
 * ({@code product_types}, seeded by V3), so {@code products.product_type_code}
 * has a real foreign key. The two sides are tied together by a startup check:
 * a row whose code this class does not know fails the context rather than
 * waiting to surface as a product that validates against nothing.
 *
 * <h2>What this replaces</h2>
 *
 * The PDP currently renders a static spec table — literal markup reading
 * "Solid 18k gold", "In our atelier", "30 days, unworn" — for every product,
 * so a watch page asserts the watch is solid 18k gold. Driving the table off
 * the product's declared type is what makes that stop.
 */
public final class ProductTypeRegistry {

    public static final String JEWELLERY = "JEWELLERY";
    public static final String WATCH = "WATCH";

    /**
     * The type of every {@link ProductKind#PACKAGING} entry, and of nothing
     * else. A box has no metal, movement or carat weight, so it declares no
     * spec fields; typing it JEWELLERY or WATCH would store a gift box as
     * something it is not, and a report grouped by type would count it as one.
     * Kind and type are tied together in {@link Product}, and again by a CHECK
     * in V5, so the two can never disagree.
     */
    public static final String PACKAGING = "PACKAGING";

    /** Generous for "Alligator Leather", tight enough to stay a spec value. */
    private static final int MAX_TEXT_LENGTH = 120;

    /**
     * Ordered, because both the admin form and the PDP spec table render in
     * this order. A {@code LinkedHashMap} of ordered lists, so "declaration
     * order" is a property of this file rather than of a sort somewhere else.
     *
     * <p>Only {@code movement} is required on a watch, and nothing is required
     * on jewellery. That is deliberate: V3 backfilled every pre-existing
     * product as {@code JEWELLERY} with {@code attributes = '{}'}, so a
     * required jewellery field would make the entire migrated catalogue
     * unsaveable until someone filled it in.
     */
    private static final Map<String, List<AttributeDefinition>> DEFINITIONS = new LinkedHashMap<>();

    static {
        DEFINITIONS.put(JEWELLERY, List.of(
                AttributeDefinition.text("metal", "Metal", false),
                AttributeDefinition.enumerated("metalPurity", "Metal purity", false,
                        "9k", "14k", "18k", "22k", "24k", "Sterling Silver", "Platinum"),
                AttributeDefinition.text("gemstone", "Gemstone", false),
                AttributeDefinition.number("caratWeight", "Carat weight", false, "ct"),
                AttributeDefinition.text("finish", "Finish", false),
                AttributeDefinition.flag("hallmarked", "Hallmarked")));

        // No spec fields: nothing about a box is asked of the client yet. If she
        // wants material, colour or size recorded, they are added here and to
        // nothing else.
        DEFINITIONS.put(PACKAGING, List.of());

        DEFINITIONS.put(WATCH, List.of(
                AttributeDefinition.enumerated("movement", "Movement", true,
                        "AUTOMATIC", "QUARTZ", "MANUAL"),
                AttributeDefinition.number("caseDiameterMm", "Case diameter", false, "mm"),
                AttributeDefinition.text("caseMaterial", "Case material", false),
                AttributeDefinition.text("strapMaterial", "Strap material", false),
                AttributeDefinition.number("waterResistanceM", "Water resistance", false, "m"),
                AttributeDefinition.flag("sapphireCrystal", "Sapphire crystal")));
    }

    private ProductTypeRegistry() {
    }

    /** The type codes this build understands; must match {@code product_types}. */
    public static Set<String> knownCodes() {
        return DEFINITIONS.keySet();
    }

    /**
     * @throws IllegalArgumentException for an unrecognised code — deliberately
     *         not an empty list, because a product with a bogus type would
     *         then validate against no rules at all
     */
    public static List<AttributeDefinition> definitionsFor(String typeCode) {
        List<AttributeDefinition> definitions = DEFINITIONS.get(typeCode);
        if (definitions == null) {
            throw new IllegalArgumentException("unknown product type: " + typeCode);
        }
        return definitions;
    }

    /**
     * Validates and coerces a product's attributes against its type, returning
     * the map that gets stored in {@code products.attributes}.
     *
     * <p>The returned map contains only declared keys, so nothing unvalidated
     * can reach the JSONB column, and values are coerced to their declared
     * kind ({@code BigDecimal} for NUMBER, {@code Boolean} for BOOLEAN) rather
     * than stored as whatever the client happened to send.
     *
     * <p>Every failure is collected before throwing. A watch has six spec
     * fields on one form; failing on the first would make an operator submit
     * six times to discover six problems.
     */
    public static Map<String, Object> validate(String typeCode, Map<String, Object> attributes) {
        List<AttributeDefinition> definitions = definitionsFor(typeCode);
        Map<String, Object> supplied = attributes == null ? Map.of() : attributes;

        var errors = new ArrayList<Map.Entry<String, String>>();
        var out = new LinkedHashMap<String, Object>();

        for (AttributeDefinition definition : definitions) {
            Object raw = supplied.get(definition.key());
            // A null is treated as absent rather than as a value. Jackson
            // serialises an untouched optional form field as null, and
            // "present but null" and "omitted" mean the same thing to an
            // operator.
            if (raw == null || (raw instanceof String s && s.isBlank())) {
                if (definition.required()) {
                    errors.add(Map.entry(definition.key(),
                            definition.label() + " is required for a " + typeCode.toLowerCase()));
                }
                continue;
            }
            try {
                out.put(definition.key(), coerce(definition, raw));
            } catch (IllegalArgumentException e) {
                errors.add(Map.entry(definition.key(), e.getMessage()));
            }
        }

        // Anything the type does not declare. Reported rather than dropped:
        // silently discarding a field an operator filled in loses their work
        // with no indication, and it is usually a sign the wrong type was
        // picked (a carat weight submitted against a watch).
        for (String key : supplied.keySet()) {
            boolean declared = definitions.stream().anyMatch(d -> d.key().equals(key));
            if (!declared) {
                errors.add(Map.entry(key, "not an attribute of a " + typeCode.toLowerCase()));
            }
        }

        if (!errors.isEmpty()) {
            throw new AttributeValidationException(errors);
        }
        return out;
    }

    private static Object coerce(AttributeDefinition definition, Object raw) {
        return switch (definition.kind()) {
            case TEXT -> {
                String text = raw.toString().trim();
                if (text.length() > MAX_TEXT_LENGTH) {
                    throw new IllegalArgumentException(
                            definition.label() + " must be at most " + MAX_TEXT_LENGTH + " characters");
                }
                yield text;
            }
            case ENUM -> {
                String value = raw.toString().trim();
                if (!definition.allowedValues().contains(value)) {
                    throw new IllegalArgumentException(
                            definition.label() + " must be one of " + definition.allowedValues());
                }
                yield value;
            }
            case NUMBER -> {
                // Form posts arrive as strings, so a numeric string is coerced
                // rather than rejected -- otherwise every spec field would need
                // the admin UI to know its type.
                try {
                    BigDecimal number = raw instanceof BigDecimal d
                            ? d
                            : new BigDecimal(raw.toString().trim());
                    if (number.signum() < 0) {
                        throw new IllegalArgumentException(
                                definition.label() + " must not be negative");
                    }
                    yield number;
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException(
                            definition.label() + " must be a number");
                }
            }
            case BOOLEAN -> {
                if (raw instanceof Boolean b) {
                    yield b;
                }
                String value = raw.toString().trim().toLowerCase();
                if (value.equals("true")) {
                    yield Boolean.TRUE;
                }
                if (value.equals("false")) {
                    yield Boolean.FALSE;
                }
                throw new IllegalArgumentException(
                        definition.label() + " must be true or false");
            }
        };
    }
}

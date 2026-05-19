package com.oriah.shipping.model;

import java.util.Set;

/**
 * Three-tier shipping zone keyed off ISO 3166-1 alpha-2 country code.
 * Real carriers have postal-code-level precision; for the demo we
 * treat all of "DOMESTIC" alike, etc.
 */
public enum Zone {
    DOMESTIC(Set.of("NG")),                      // Nigeria — Oriah's home market
    REGIONAL(Set.of("GH", "KE", "ZA", "EG")),    // selected African neighbours
    INTERNATIONAL(Set.of());                     // everything else (fallback in classify)

    private final Set<String> countries;

    Zone(Set<String> countries) {
        this.countries = countries;
    }

    public static Zone classify(String countryCode) {
        if (countryCode == null) return INTERNATIONAL;
        String upper = countryCode.toUpperCase();
        for (Zone z : values()) {
            if (z.countries.contains(upper)) return z;
        }
        return INTERNATIONAL;
    }
}

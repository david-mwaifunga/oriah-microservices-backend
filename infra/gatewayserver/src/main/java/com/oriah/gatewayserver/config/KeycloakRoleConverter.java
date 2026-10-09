package com.oriah.gatewayserver.config;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Maps Keycloak's {@code realm_access.roles} claim onto Spring Security
 * authorities, mirroring the course's {@code KeycloakRoleConverter}.
 *
 * <p>Keycloak puts realm roles in a nested claim that Spring Security knows
 * nothing about, so without a converter a perfectly valid token produces an
 * authenticated principal with zero authorities and every role check fails.
 *
 * <h2>The contract this has to match exactly</h2>
 *
 * <p>All 12 services convert the same claim to {@code "ROLE_" +
 * value.toUpperCase()}, and all 20 {@code @PreAuthorize} annotations in the
 * repo use {@code hasRole('X')} — there is not one {@code hasAuthority} in the
 * codebase. {@code hasRole('X')} resolves against the authority
 * {@code ROLE_X}, so:
 *
 * <ul>
 *   <li>the {@code ROLE_} prefix is mandatory here, and</li>
 *   <li>the gateway's own rules must use {@code hasRole("X")} <em>without</em>
 *       the prefix.</li>
 * </ul>
 *
 * <p>Mixing those two conventions is the easiest way to silently break every
 * authenticated call: the failure is a blanket 403 with a valid token, which
 * looks like a token problem rather than a prefix problem.
 *
 * <h2>Defensive on purpose</h2>
 *
 * <p>The course version is
 * {@code ((List<String>) realmAccess.get("roles"))} behind an unchecked cast
 * with no null or type checks. Three token shapes take it down from inside the
 * authentication converter, where the failure surfaces as a 500 rather than a
 * 401:
 *
 * <ul>
 *   <li>{@code realm_access} absent → handled there, but only by luck of
 *       ordering;</li>
 *   <li>{@code realm_access} present with no {@code roles} key →
 *       {@code NullPointerException};</li>
 *   <li>either claim shaped unexpectedly → {@code ClassCastException}.</li>
 * </ul>
 *
 * <p>An edge component sees whatever tokens the internet sends it, so each of
 * those is a test here rather than a hypothetical.
 *
 * <p>Note this reads realm roles only, not {@code resource_access} (client
 * roles) — matching the services, which do the same. If client roles are ever
 * introduced, all 13 places need updating together, which is the argument for
 * eventually extracting this into the shared starter: the gateway makes copy
 * number 13 of identical logic that currently exists under two different
 * method names.
 */
public class KeycloakRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    private static final String REALM_ACCESS_CLAIM = "realm_access";
    private static final String ROLES_KEY = "roles";
    private static final String ROLE_PREFIX = "ROLE_";

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Object realmAccess = jwt.getClaim(REALM_ACCESS_CLAIM);
        if (!(realmAccess instanceof Map<?, ?> realmAccessMap)) {
            // Covers both "claim absent" and "claim is not an object".
            return List.of();
        }

        Object roles = realmAccessMap.get(ROLES_KEY);
        if (!(roles instanceof Collection<?> roleValues)) {
            // Covers both "no roles key" and "roles is not an array".
            return List.of();
        }

        return roleValues.stream()
                // Keycloak sends strings; anything else is skipped rather than
                // stringified, which would otherwise yield authorities like
                // ROLE_42 or ROLE_TRUE that can never match a rule.
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(role -> !role.isBlank())
                .map(role -> ROLE_PREFIX + role.toUpperCase(Locale.ROOT))
                .<GrantedAuthority>map(SimpleGrantedAuthority::new)
                .toList();
    }
}

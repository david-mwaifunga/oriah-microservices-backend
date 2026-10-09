package com.oriah.gatewayserver.config;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Unit tests for the gateway's Keycloak claim → authority mapping.
 *
 * <p>Getting this wrong is the single easiest way to silently break every
 * authenticated call through the edge, because the failure mode is a blanket
 * 403 with a valid token — which reads as "the token is wrong" rather than
 * "the prefix is wrong".
 *
 * <p>The sweep of all 12 services established the contract this must match
 * exactly: every service converts {@code realm_access.roles} to
 * {@code "ROLE_" + value.toUpperCase()}, and every one of the 20
 * {@code @PreAuthorize} annotations in the repo uses {@code hasRole('X')} —
 * there is not a single {@code hasAuthority} anywhere. So the prefix is
 * mandatory, and the gateway's own rules must use {@code hasRole} without it.
 */
class KeycloakRoleConverterTest {

    private final KeycloakRoleConverter converter = new KeycloakRoleConverter();

    @Test
    @DisplayName("realm roles become ROLE_-prefixed, upper-cased authorities")
    void mapsRealmRolesToPrefixedAuthorities() {
        Jwt jwt = jwtWithClaims(Map.of(
                "realm_access", Map.of("roles", List.of("USER", "CATALOG_ADMIN"))));

        assertThat(authorityNames(converter.convert(jwt)))
                .as("the 12 services all guard with hasRole('X'), which resolves "
                        + "against authority ROLE_X; omitting the prefix 403s every call")
                .contains("ROLE_USER", "ROLE_CATALOG_ADMIN");
    }

    @Test
    @DisplayName("lower-case or mixed-case realm roles are upper-cased")
    void upperCasesRoleNames() {
        // Every service's converter uppercases, so roles work in any case today
        // because the realm happens to store them uppercase. A gateway that
        // compared raw values would diverge the moment a role is seeded mixed-case.
        Jwt jwt = jwtWithClaims(Map.of(
                "realm_access", Map.of("roles", List.of("user", "Catalog_Admin"))));

        assertThat(authorityNames(converter.convert(jwt)))
                .contains("ROLE_USER", "ROLE_CATALOG_ADMIN");
    }

    @Test
    @DisplayName("a token with no realm_access claim yields no authorities, not an exception")
    void missingRealmAccessClaimIsEmpty() {
        // The course's converter returns new ArrayList<>() here, which is fine,
        // but it reaches that path only after an unchecked cast that throws on
        // any unexpected shape.
        Jwt jwt = jwtWithClaims(Map.of("sub", "maya"));

        assertThat(converter.convert(jwt)).isEmpty();
    }

    @Test
    @DisplayName("realm_access present but with no roles key does not NPE")
    void realmAccessWithoutRolesKeyIsEmpty() {
        // The course does ((List<String>) realmAccess.get("roles")) with no null
        // check, so this shape throws NullPointerException from inside the
        // authentication converter — a 500 on every request carrying such a token.
        Jwt jwt = jwtWithClaims(Map.of("realm_access", Map.of("other", "value")));

        assertThatCode(() -> converter.convert(jwt)).doesNotThrowAnyException();
        assertThat(converter.convert(jwt)).isEmpty();
    }

    @Test
    @DisplayName("an unexpectedly shaped realm_access claim does not ClassCastException")
    void wronglyShapedClaimIsEmpty() {
        // The course casts realm_access to Map and roles to List<String> with no
        // type checks. A token where either is something else takes down the
        // request with a ClassCastException.
        Jwt jwt = jwtWithClaims(Map.of("realm_access", "not-a-map"));

        assertThatCode(() -> converter.convert(jwt)).doesNotThrowAnyException();
        assertThat(converter.convert(jwt)).isEmpty();
    }

    @Test
    @DisplayName("non-string entries in the roles list are skipped, not rendered as ROLE_NULL")
    void nonStringRoleEntriesAreSkipped() {
        Jwt jwt = jwtWithClaims(Map.of(
                "realm_access", Map.of("roles", List.of("USER", 42, true))));

        assertThat(authorityNames(converter.convert(jwt)))
                .contains("ROLE_USER")
                .noneMatch(name -> name.contains("42") || name.contains("TRUE"));
    }

    @Test
    @DisplayName("Keycloak's own default roles come through without special-casing")
    void keycloakDefaultRolesAreMapped() {
        // offline_access and uma_authorization are in every token this realm
        // issues (they are composites of default-roles-oriah). They guard
        // nothing, but they must not break the mapping.
        Jwt jwt = jwtWithClaims(Map.of(
                "realm_access", Map.of("roles",
                        List.of("offline_access", "uma_authorization", "USER"))));

        assertThat(authorityNames(converter.convert(jwt)))
                .contains("ROLE_OFFLINE_ACCESS", "ROLE_UMA_AUTHORIZATION", "ROLE_USER");
    }

    private static List<String> authorityNames(Collection<GrantedAuthority> authorities) {
        return authorities.stream().map(GrantedAuthority::getAuthority).toList();
    }

    private static Jwt jwtWithClaims(Map<String, Object> claims) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .issuedAt(Instant.EPOCH)
                .expiresAt(Instant.EPOCH.plusSeconds(3600))
                .claims(c -> c.putAll(claims))
                .build();
    }
}

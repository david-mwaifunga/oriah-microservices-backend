package com.oriah.payment.confirmation;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Who is acting: the actor as the event log records it ({@code user:<keycloak subject>}, the record) and the name the token carried, if any (kept
 * trimmed and capped, and null when there is none). A person is never identified by the name.
 */
public record Staff(String actor, String name, boolean mayBackdate) {

    private static final int NAME_LIMIT = 100;

    public static Staff of(Jwt jwt, boolean mayBackdate) {
        String name = jwt.getClaimAsString("name");
        if (name != null) {
            name = name.strip();
            if (name.isEmpty()) {
                name = null;
            } else if (name.length() > NAME_LIMIT) {
                name = name.substring(0, NAME_LIMIT);
            }
        }
        return new Staff("user:" + jwt.getSubject(), name, mayBackdate);
    }
}

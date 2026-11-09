package com.oriah.order.controller;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Who is acting, as the event log records it: {@code user:} and the caller's
 * Keycloak subject -- the customer on their own order, the member of staff on
 * an admin transition. Taken from the token, never from the request body, so
 * nobody can act in someone else's name.
 */
final class Actor {

    private Actor() {
    }

    static String of(Jwt jwt) {
        return "user:" + jwt.getSubject();
    }
}

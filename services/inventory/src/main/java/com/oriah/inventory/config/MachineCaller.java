package com.oriah.inventory.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * Whether the caller is the order system, not a person (Phase 7). A hold, a sale and a release are made by the order system alone: the
 * oriah-admin service account holds INVENTORY_ADMIN, and so do an owner and a manager, who must not be able to fabricate a sale or let go
 * another customer's hold by reaching inventory directly. The gateway denies the reservation prefix; this is the second wall, in the
 * service, for the day a port is open.
 *
 * <p>The test is the token's {@code preferred_username}, which Keycloak sets to {@code service-account-<client id>} for a client's service
 * account. It is NOT {@code azp} (the client the token was issued to): a person signing in through the same client carries the same
 * {@code azp}.
 */
@Component("machineCaller")
public class MachineCaller {

    private final String serviceAccount;

    public MachineCaller(@Value("${oriah.inventory.machine-caller:service-account-oriah-admin}") String serviceAccount) {
        this.serviceAccount = serviceAccount;
    }

    public boolean isMachine(Authentication authentication) {
        return authentication != null
                && authentication.getPrincipal() instanceof Jwt jwt
                && serviceAccount.equals(jwt.getClaimAsString("preferred_username"));
    }
}

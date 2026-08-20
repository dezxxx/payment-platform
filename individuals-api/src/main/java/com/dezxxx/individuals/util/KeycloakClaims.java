package com.dezxxx.individuals.util;

import java.util.List;
import java.util.Map;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Reads Keycloak-specific claims out of a decoded access token.
 *
 * <p>Keycloak does not put realm roles where Spring Security looks for
 * authorities by default: they live in the nested {@code realm_access.roles}
 * claim, not in {@code scope}. That layout is a Keycloak detail, so it is
 * described in exactly one place - here - and nothing else in the service is
 * allowed to reach into the claim map by hand.
 *
 * <p>Every method is defensive. A claim comes from parsed JSON, which means the
 * key may be absent and the value may be of any type; a missing or unexpected
 * value is an empty answer, never an exception. The token has already been
 * verified by the resource server at this point, so the only realistic cause is
 * a differently configured realm, and that must not turn into a 500.
 */
public final class KeycloakClaims {

    private static final String REALM_ACCESS = "realm_access";

    private static final String ROLES = "roles";

    private KeycloakClaims() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Realm roles carried by the token, in the order Keycloak wrote them.
     *
     * <p>The list is returned as it stands, including the roles Keycloak adds
     * on its own ({@code default-roles-*}, {@code offline_access},
     * {@code uma_authorization}). Deciding which of them are worth showing to a
     * client is a business call and belongs to the caller.
     *
     * @param jwt decoded access token
     * @return immutable list of role names, empty when the token carries none
     */
    public static List<String> realmRoles(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaimAsMap(REALM_ACCESS);
        if (realmAccess == null) {
            return List.of();
        }
        if (!(realmAccess.get(ROLES) instanceof List<?> roles)) {
            return List.of();
        }
        return roles.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .toList();
    }
}

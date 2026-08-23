package com.dezxxx.individuals.util;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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

    /** Written by the realm's protocol mapper - see {@code realm-export.json}. */
    private static final String USER_UID = "user_uid";

    private KeycloakClaims() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * The platform identifier carried by the token.
     *
     * <p>Absent when the realm's {@code user_uid} mapper is missing or the
     * account predates it, and unparseable if something wrote a non-UUID into
     * the attribute. Both are configuration faults rather than caller mistakes,
     * so they answer empty and let the caller decide - a 500 raised from a claim
     * reader would say nothing useful.
     */
    public static Optional<UUID> userUid(Jwt jwt) {
        String raw = jwt.getClaimAsString(USER_UID);
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(raw));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
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

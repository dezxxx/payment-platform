package com.dezxxx.individuals.util;

import com.dezxxx.individuals.error.ApiException;
import com.dezxxx.individuals.error.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jwt.Jwt;

// Reads Keycloak's claims from a verified token. Realm roles sit in the nested
// realm_access.roles claim - only this class knows that. Defensive: a missing
// or odd claim gives an empty answer, never an exception
@Slf4j
public final class KeycloakClaims {

    private static final String REALM_ACCESS = "realm_access";

    private static final String ROLES = "roles";

    // written by the realm's protocol mapper (realm-export.json)
    private static final String USER_UID = "user_uid";

    private KeycloakClaims() {
        throw new UnsupportedOperationException("Utility class");
    }

    // user_uid from the token; empty if the mapper is missing or the value is not a UUID
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

    // user_uid that must be there - used by login, refresh and /me.
    // Missing means the realm's mapper is broken: our fault, so 500, not 401.
    public static UUID requireUserUid(Jwt jwt) {
        return userUid(jwt).orElseThrow(() -> {
            log.error("Access token of {} carries no usable user_uid claim", jwt.getSubject());
            return new ApiException(ErrorCode.INTERNAL_ERROR);
        });
    }

    // all realm roles, Keycloak's own included - the caller decides what to show
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

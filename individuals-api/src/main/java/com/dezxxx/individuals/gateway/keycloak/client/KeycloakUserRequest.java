package com.dezxxx.individuals.gateway.keycloak.client;

import java.util.List;
import java.util.Map;

// Body of POST /admin/realms/{realm}/users. No password - it is set by a second call
record KeycloakUserRequest(
        String username,
        String email,
        String firstName,
        String lastName,
        boolean enabled,
        boolean emailVerified,
        Map<String, List<String>> attributes) {

    // must match the user_uid protocol mapper in realm-export.json,
    // otherwise the claim silently disappears from the token
    private static final String USER_UID_ATTRIBUTE = "user_uid";

    // username = email (the realm logs in by email); attribute values are
    // always lists; emailVerified is false - nothing verified it
    static KeycloakUserRequest forRegistration(String email, String firstName, String lastName, String userUid) {
        return new KeycloakUserRequest(
                email,
                email,
                firstName,
                lastName,
                true,
                false,
                Map.of(USER_UID_ATTRIBUTE, List.of(userUid)));
    }
}

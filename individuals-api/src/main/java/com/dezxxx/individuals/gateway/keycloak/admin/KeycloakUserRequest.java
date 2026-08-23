package com.dezxxx.individuals.gateway.keycloak.admin;

import java.util.List;
import java.util.Map;

/**
 * Body of {@code POST /admin/realms/{realm}/users}.
 *
 * <p>Keycloak's {@code UserRepresentation} has some fifty optional fields;
 * these seven are what we send. No password - it is set by a second call, see
 * {@link KeycloakCredential}.
 */
public record KeycloakUserRequest(
        String username,
        String email,
        String firstName,
        String lastName,
        boolean enabled,
        boolean emailVerified,
        Map<String, List<String>> attributes) {

    /**
     * Must stay equal to {@code user.attribute} of the {@code user_uid}
     * protocol mapper in {@code realm/realm-export.json}. If they disagree the
     * user is still created, but the claim never appears in the token and
     * {@code /me} loses the field silently.
     */
    private static final String USER_UID_ATTRIBUTE = "user_uid";

    /**
     * The body for a fresh registration. Three Keycloak quirks live here:
     *
     * <ul>
     *   <li>{@code username = email} - Keycloak requires a username and our
     *       contract has none; the realm sets
     *       {@code registrationEmailAsUsername}, so the address serves;</li>
     *   <li>attribute values are always arrays, even for a single-valued
     *       attribute - a bare string is answered <b>400 (Bad Request)</b>;</li>
     *   <li>{@code emailVerified = false} - nothing verified it, and the lie
     *       would travel in every token this account ever gets.</li>
     * </ul>
     */
    public static KeycloakUserRequest forRegistration(String email,
                                                      String firstName,
                                                      String lastName,
                                                      String userUid) {
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

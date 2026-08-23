package com.dezxxx.individuals.gateway.keycloak.admin;

/**
 * Body of {@code PUT /admin/realms/{realm}/users/{id}/reset-password}.
 */
public record KeycloakCredential(String type, String value, boolean temporary) {

    private static final String PASSWORD = "password";

    /**
     * A permanent password.
     *
     * <p>{@code temporary} must be false. Left at Keycloak's default of true
     * everything still looks successful - the reset answers
     * <b>204 (No Content)</b> - and the next login fails with
     * <b>400 (Bad Request)</b> / {@code invalid_grant} "Account is not fully
     * set up", because Keycloak expects the password to be changed on its own
     * login page, which an API-only client never shows.
     */
    public static KeycloakCredential password(String value) {
        return new KeycloakCredential(PASSWORD, value, false);
    }
}

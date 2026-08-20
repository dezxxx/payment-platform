package com.dezxxx.individuals.gateway.keycloak;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The body Keycloak sends with a failure.
 *
 * <p>Two different shapes arrive here, which is why both fields are optional.
 * The OIDC endpoints answer in the OAuth 2 style:
 *
 * <pre>{"error": "invalid_grant", "error_description": "Invalid user credentials"}</pre>
 *
 * while the Admin API answers with a single message:
 *
 * <pre>{"errorMessage": "User exists with same email"}</pre>
 *
 * <p>Why this matters: the HTTP status alone is not enough to tell a wrong
 * password from a half-configured account - Keycloak answers 400 with
 * {@code invalid_grant} to both, and only the description separates them.
 */
public record KeycloakErrorResponse(

        String error,

        @JsonProperty("error_description") String errorDescription,

        @JsonProperty("errorMessage") String errorMessage) {

    /** Used when the failure carried no body, or a body we could not read. */
    public static final KeycloakErrorResponse EMPTY = new KeycloakErrorResponse(null, null, null);

    /**
     * The most informative text Keycloak gave us, whichever field it used.
     * For the log only - it is never shown to a client.
     */
    public String describe() {
        if (errorDescription != null) {
            return errorDescription;
        }
        if (errorMessage != null) {
            return errorMessage;
        }
        return error == null ? "no details" : error;
    }
}

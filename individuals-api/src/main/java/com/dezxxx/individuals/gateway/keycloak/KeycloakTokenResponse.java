package com.dezxxx.individuals.gateway.keycloak;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The token endpoint's answer, in Keycloak's own shape.
 *
 * <p>Written by hand rather than generated: Keycloak publishes no OpenAPI
 * document for the OIDC endpoints, so there is nothing to feed a generator.
 *
 * <p>Deliberately narrower than the real payload. Keycloak also returns
 * {@code refresh_expires_in}, {@code not-before-policy}, {@code session_state}
 * and {@code scope}; none of them appear in our contract, and Jackson ignores
 * what is not declared here. Fewer fields means fewer things that can break
 * when Keycloak is upgraded.
 *
 * <p>This type never leaves the gateway package. Everything above it speaks in
 * {@code TokenResponse}, the type our own contract defines.
 */
public record KeycloakTokenResponse(

        @JsonProperty("access_token") String accessToken,

        @JsonProperty("refresh_token") String refreshToken,

        // Lifetime of the access token in seconds, not a point in time.
        @JsonProperty("expires_in") Long expiresIn,

        @JsonProperty("token_type") String tokenType) {
}

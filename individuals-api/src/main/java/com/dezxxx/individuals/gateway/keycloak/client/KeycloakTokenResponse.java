package com.dezxxx.individuals.gateway.keycloak.client;

import com.fasterxml.jackson.annotation.JsonProperty;

// The token endpoint's answer in Keycloak's shape. Written by hand: Keycloak
// has no OpenAPI spec for OIDC. Only the fields we use; the rest is ignored.
// Never leaves KeycloakClient - above it everything speaks TokenResponse.
record KeycloakTokenResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("refresh_token") String refreshToken,
        // lifetime in seconds, not a point in time
        @JsonProperty("expires_in") Long expiresIn,
        @JsonProperty("token_type") String tokenType) {
}

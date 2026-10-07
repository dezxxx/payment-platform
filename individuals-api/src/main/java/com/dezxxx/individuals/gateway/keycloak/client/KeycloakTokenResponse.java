package com.dezxxx.individuals.gateway.keycloak.client;

import com.fasterxml.jackson.annotation.JsonProperty;

// written by hand: Keycloak has no OpenAPI spec for OIDC
record KeycloakTokenResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("refresh_token") String refreshToken,
        @JsonProperty("expires_in") Long expiresIn,
        @JsonProperty("token_type") String tokenType) {
}

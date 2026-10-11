package com.dezxxx.individuals.gateway.keycloak.client;

import com.fasterxml.jackson.annotation.JsonProperty;

record KeycloakTokenResponseDto(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("refresh_token") String refreshToken,
        @JsonProperty("expires_in") Long expiresIn,
        @JsonProperty("token_type") String tokenType) {
}

package com.dezxxx.individuals.config;

import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

// Keycloak settings from individuals.keycloak.*, checked at startup:
// a missing or blank value stops the app, not the first request.
// clientSecret comes from .env, never from a committed file
@Validated
@ConfigurationProperties(prefix = "individuals.keycloak")
public record KeycloakProperties(
        @NotBlank String baseUrl,
        @NotBlank String realm,
        @NotBlank String clientId,
        @NotBlank String clientSecret,
        @DefaultValue("5s") Duration responseTimeout) {

    // token endpoint: login, refresh, service-account token
    public String tokenUri() {
        return "/realms/" + realm + "/protocol/openid-connect/token";
    }

    // Admin API: users
    public String usersUri() {
        return "/admin/realms/" + realm + "/users";
    }

    // Admin API: realm roles, by name
    public String rolesUri() {
        return "/admin/realms/" + realm + "/roles";
    }
}

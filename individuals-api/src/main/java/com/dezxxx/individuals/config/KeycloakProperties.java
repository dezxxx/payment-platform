package com.dezxxx.individuals.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

// Keycloak settings from individuals.keycloak.*, checked at startup.
// clientSecret comes from .env, never from a committed file
@ConfigurationProperties(prefix = "individuals.keycloak")
public record KeycloakProperties(
        String baseUrl,
        String realm,
        String clientId,
        String clientSecret,
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

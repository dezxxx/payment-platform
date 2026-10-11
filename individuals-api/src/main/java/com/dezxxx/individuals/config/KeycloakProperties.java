package com.dezxxx.individuals.config;

import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "individuals.keycloak")
public record KeycloakProperties(
        @NotBlank String baseUrl,
        @NotBlank String realm,
        @NotBlank String clientId,
        @NotBlank String clientSecret,
        @DefaultValue("5s") Duration responseTimeout) {

    public String tokenUri() {
        return "/realms/" + realm + "/protocol/openid-connect/token";
    }

    public String usersUri() {
        return "/admin/realms/" + realm + "/users";
    }

    public String rolesUri() {
        return "/admin/realms/" + realm + "/roles";
    }
}

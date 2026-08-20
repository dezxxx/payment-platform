package com.dezxxx.individuals.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Everything the service needs to talk to Keycloak, bound from
 * {@code individuals.keycloak.*}.
 *
 * <p>A record rather than {@code @Value} on scattered fields: the keys are
 * spelled once, binding happens at startup instead of at the first call, and a
 * typo fails the context rather than a request.
 *
 * @param baseUrl         root of the Keycloak instance, no trailing slash
 * @param realm           realm that owns the users and the client
 * @param clientId        confidential client of this service
 * @param clientSecret    its secret; comes from .env, never from a committed file
 * @param responseTimeout how long a single call may take before it is failed
 */
@ConfigurationProperties(prefix = "individuals.keycloak")
public record KeycloakProperties(
        String baseUrl,
        String realm,
        String clientId,
        String clientSecret,
        @DefaultValue("5s") Duration responseTimeout) {

    /** Token endpoint of the realm - login, refresh and client credentials. */
    public String tokenUri() {
        return "/realms/" + realm + "/protocol/openid-connect/token";
    }

    /** Admin REST API base for user operations of this realm. */
    public String usersUri() {
        return "/admin/realms/" + realm + "/users";
    }
}

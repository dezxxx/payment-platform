package com.dezxxx.individuals.config;

import com.dezxxx.individuals.error.ApiAccessDeniedHandler;
import com.dezxxx.individuals.error.ApiAuthenticationEntryPoint;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;

/**
 * Security of the external entry layer.
 *
 * <p>The service is a resource server: it never issues a token, it only
 * verifies the ones Keycloak issued. Verification uses the realm public keys,
 * fetched from the issuer declared in {@code application.yml} - the Keycloak
 * private key never leaves Keycloak.
 *
 * <p>Reactive stack, so this is a {@link SecurityWebFilterChain} built from
 * {@link ServerHttpSecurity}. The servlet equivalents (SecurityFilterChain,
 * HttpSecurity, requestMatchers) do not apply to WebFlux and would silently do
 * nothing if declared.
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    /**
     * Reached before the caller holds a token, so they cannot be protected.
     * Listed one by one on purpose: a wildcard here would silently open every
     * endpoint added under the same prefix later on.
     */
    private static final String[] PUBLIC_ENDPOINTS = {
            "/v1/auth/registration",
            "/v1/auth/login",
            "/v1/auth/refresh-token"
    };

    /**
     * Infrastructure paths. Wildcards are fine here because the set of paths is
     * decided by the frameworks, not by us, and because what exists under
     * /actuator is governed separately by management.endpoints.web.exposure.
     */
    private static final String[] INFRASTRUCTURE_ENDPOINTS = {
            "/actuator/health/**",
            "/actuator/info",
            "/actuator/prometheus",
            "/v3/api-docs/**",
            // Not covered by /swagger-ui/** - a dot, not a slash.
            "/swagger-ui.html",
            "/swagger-ui/**",
            // WebFlux serves the UI from this path rather than from a static
            // resource, and springdoc redirects /swagger-ui.html into it.
            "/webjars/**"
    };

    @Bean
    SecurityWebFilterChain filterChain(ServerHttpSecurity http,
                                       ApiAuthenticationEntryPoint authenticationEntryPoint,
                                       ApiAccessDeniedHandler accessDeniedHandler) {
        return http
                // Tokens travel in the Authorization header, never in a cookie,
                // so there is nothing a browser could submit on the user's behalf.
                .csrf(ServerHttpSecurity.CsrfSpec::disable)

                // Neither belongs to a token-only API: without this a browser
                // gets a login form or a basic-auth popup instead of our JSON.
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)

                // No session: every request proves itself with its own token.
                // The reactive stack has no SessionCreationPolicy - refusing to
                // store the SecurityContext is how the same thing is said here.
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())

                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers(PUBLIC_ENDPOINTS).permitAll()
                        .pathMatchers(INFRASTRUCTURE_ENDPOINTS).permitAll()
                        // Must stay last: the first matching rule wins.
                        .anyExchange().authenticated())

                // Validates the signature against the realm JWKS, plus exp and iss.
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))

                // Failures raised inside the filter chain never reach a handler,
                // so these two write the contract's error body themselves.
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))

                .build();
    }
}

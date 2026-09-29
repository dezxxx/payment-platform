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

// Resource server: we never issue tokens, only verify Keycloak's with the
// realm's public keys. WebFlux, so SecurityWebFilterChain + ServerHttpSecurity
// (the servlet HttpSecurity would silently do nothing here).
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    // called before the caller has a token. One by one on purpose:
    // a wildcard would silently open any endpoint added later
    private static final String[] PUBLIC_ENDPOINTS = {
            "/api/v1/auth/registration",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh-token"
    };

    // infrastructure; wildcards are fine - these paths belong to the frameworks
    private static final String[] INFRASTRUCTURE_ENDPOINTS = {
            "/actuator/health/**",
            "/actuator/info",
            "/actuator/prometheus",
            "/v3/api-docs/**",
            // our contract file, shown by the Swagger UI
            "/openapi/**",
            // not covered by /swagger-ui/** - a dot, not a slash
            "/swagger-ui.html",
            "/swagger-ui/**",
            // WebFlux serves the Swagger UI from here
            "/webjars/**"
    };

    @Bean
    SecurityWebFilterChain filterChain(ServerHttpSecurity http,
                                       ApiAuthenticationEntryPoint authenticationEntryPoint,
                                       ApiAccessDeniedHandler accessDeniedHandler) {
        return http
                // tokens go in the Authorization header, never in a cookie
                .csrf(ServerHttpSecurity.CsrfSpec::disable)

                // token-only API: no login form, no basic-auth popup
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)

                // no session: every request brings its own token
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())

                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers(PUBLIC_ENDPOINTS).permitAll()
                        .pathMatchers(INFRASTRUCTURE_ENDPOINTS).permitAll()
                        // must stay last: the first matching rule wins
                        .anyExchange().authenticated())

                // checks the signature (realm keys), exp and iss. A token that is
                // present but broken fails here, not in exceptionHandling below -
                // so our entry point is set here too, or the answer is an empty 401
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .jwt(Customizer.withDefaults()))

                // errors inside the filter chain never reach GlobalExceptionHandler,
                // so these two write our error body themselves
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))

                .build();
    }
}

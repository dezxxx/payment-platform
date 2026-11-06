package com.dezxxx.transaction.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.ExpressionJwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.servlet.HandlerExceptionResolver;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String SERVICE_ROLE = "SERVICE";

    // Keycloak puts realm roles into realm_access.roles; a token without them gets no role
    private static final String REALM_ROLES = "['realm_access']?.['roles'] ?: {}";

    private static final String ROLE_PREFIX = "ROLE_";

    private static final String[] INFRASTRUCTURE_ENDPOINTS = {
            "/actuator/health/**",
            "/actuator/info",
            "/actuator/prometheus",
            "/actuator/metrics/**",
            "/v3/api-docs/**",
            "/openapi/**",
            "/swagger-ui.html",
            "/swagger-ui/**"
    };

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http,
                                    @Qualifier("handlerExceptionResolver") HandlerExceptionResolver resolver) {
        // 401 and 403 go through GlobalExceptionHandler, so they have the same body as every other error
        AuthenticationEntryPoint entryPoint = (request, response, ex) ->
                resolver.resolveException(request, response, null, ex);
        AccessDeniedHandler accessDenied = (request, response, ex) ->
                resolver.resolveException(request, response, null, ex);
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers(INFRASTRUCTURE_ENDPOINTS).permitAll()
                        .anyRequest().hasRole(SERVICE_ROLE))
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDenied)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(realmRoles())))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDenied))
                .build();
    }

    private static JwtAuthenticationConverter realmRoles() {
        ExpressionJwtGrantedAuthoritiesConverter roles =
                new ExpressionJwtGrantedAuthoritiesConverter(new SpelExpressionParser().parseExpression(REALM_ROLES));
        roles.setAuthorityPrefix(ROLE_PREFIX);
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(roles);
        return converter;
    }
}

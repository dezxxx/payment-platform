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

@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    private static final String[] PUBLIC_ENDPOINTS = {
            "/api/v1/auth/registration",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh-token"
    };

    private static final String[] INFRASTRUCTURE_ENDPOINTS = {
            "/actuator/health/**",
            "/actuator/info",
            "/actuator/prometheus",
            "/v3/api-docs/**",
            "/openapi/**",
            // not covered by /swagger-ui/** - a dot, not a slash
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/webjars/**"
    };

    @Bean
    SecurityWebFilterChain filterChain(ServerHttpSecurity http,
                                       ApiAuthenticationEntryPoint authenticationEntryPoint,
                                       ApiAccessDeniedHandler accessDeniedHandler) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)

                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)

                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())

                .authorizeExchange(exchanges -> exchanges
                        .pathMatchers(PUBLIC_ENDPOINTS).permitAll()
                        .pathMatchers(INFRASTRUCTURE_ENDPOINTS).permitAll()
                        .anyExchange().authenticated())

                // a broken token fails here, so without this entry point the 401 has no body
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .jwt(Customizer.withDefaults()))

                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))

                .build();
    }
}

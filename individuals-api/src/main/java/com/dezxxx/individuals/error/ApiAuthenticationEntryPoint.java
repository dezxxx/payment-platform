package com.dezxxx.individuals.error;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Reports a missing or invalid token in the shape the contract requires.
 *
 * <p>Authentication happens in a web filter, before the request is routed to a
 * handler, so {@link GlobalExceptionHandler} never sees the failure and Spring
 * Security would answer with an empty 401 and a {@code WWW-Authenticate}
 * header. The body is written through {@link SecurityErrorWriter}, which builds
 * it with the same factory the advice uses.
 */
@Component
@RequiredArgsConstructor
public class ApiAuthenticationEntryPoint implements ServerAuthenticationEntryPoint {

    private final SecurityErrorWriter securityErrorWriter;

    @Override
    public Mono<Void> commence(ServerWebExchange exchange, AuthenticationException ex) {
        return securityErrorWriter.write(exchange, ErrorCode.INVALID_CREDENTIALS);
    }
}

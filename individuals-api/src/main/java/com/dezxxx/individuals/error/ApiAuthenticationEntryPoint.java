package com.dezxxx.individuals.error;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
@RequiredArgsConstructor
public class ApiAuthenticationEntryPoint implements ServerAuthenticationEntryPoint {

    private final SecurityErrorWriter securityErrorWriter;

    // an unknown path lands here too: it needs a token before routing
    @Override
    public Mono<Void> commence(ServerWebExchange exchange, AuthenticationException ex) {
        return securityErrorWriter.write(exchange, ErrorCode.AUTHENTICATION_REQUIRED);
    }
}

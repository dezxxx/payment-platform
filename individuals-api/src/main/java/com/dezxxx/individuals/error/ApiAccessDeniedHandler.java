package com.dezxxx.individuals.error;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

// 403: the token is valid but its roles are not enough. Needed because the
// decision is made in a web filter, out of GlobalExceptionHandler's reach
@Component
@RequiredArgsConstructor
public class ApiAccessDeniedHandler implements ServerAccessDeniedHandler {

    private final SecurityErrorWriter securityErrorWriter;

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, AccessDeniedException ex) {
        return securityErrorWriter.write(exchange, ErrorCode.ACCESS_DENIED);
    }
}

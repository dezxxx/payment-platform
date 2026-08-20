package com.dezxxx.individuals.error;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Reports an authenticated caller who is not allowed through.
 *
 * <p>The counterpart of {@link ApiAuthenticationEntryPoint}: the token is
 * valid, the authorities are not sufficient. Same reason for existing - the
 * decision is taken in a web filter, where no advice can reach it.
 */
@Component
@RequiredArgsConstructor
public class ApiAccessDeniedHandler implements ServerAccessDeniedHandler {

    private final SecurityErrorWriter securityErrorWriter;

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, AccessDeniedException ex) {
        return securityErrorWriter.write(exchange, ErrorCode.ACCESS_DENIED);
    }
}

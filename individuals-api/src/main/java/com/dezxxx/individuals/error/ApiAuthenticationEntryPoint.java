package com.dezxxx.individuals.error;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

// 401 in our error shape. Authentication runs in a web filter, out of
// GlobalExceptionHandler's reach - without this Spring answers an empty 401
@Component
@RequiredArgsConstructor
public class ApiAuthenticationEntryPoint implements ServerAuthenticationEntryPoint {

    private final SecurityErrorWriter securityErrorWriter;

    // also an unknown path lands here (it needs a token before routing),
    // so the answer is "token required", not "wrong password"
    @Override
    public Mono<Void> commence(ServerWebExchange exchange, AuthenticationException ex) {
        return securityErrorWriter.write(exchange, ErrorCode.AUTHENTICATION_REQUIRED);
    }
}

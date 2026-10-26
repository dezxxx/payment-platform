package com.dezxxx.individuals.util;

import com.dezxxx.individuals.error.ApiException;
import com.dezxxx.individuals.error.ErrorCode;
import com.dezxxx.individuals.logging.RequestLog;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

// who sent the request: the verified token in the controller, its user_uid in the service
public final class CurrentUser {

    private CurrentUser() {
        throw new UnsupportedOperationException("Utility class");
    }

    public static Mono<Jwt> jwt(ServerWebExchange exchange) {
        return exchange.getPrincipal()
                .cast(JwtAuthenticationToken.class)
                .map(JwtAuthenticationToken::getToken)
                .switchIfEmpty(Mono.error(() -> new ApiException(ErrorCode.AUTHENTICATION_REQUIRED)));
    }

    public static Mono<UUID> userUid(Jwt jwt) {
        return Mono.fromCallable(() -> KeycloakClaims.requireUserUid(jwt))
                .doOnNext(RequestLog::userUid);
    }
}

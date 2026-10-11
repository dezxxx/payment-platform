package com.dezxxx.individuals.service;

import com.dezxxx.individuals.api.model.TokenResponseDto;
import com.dezxxx.individuals.error.ApiException;
import com.dezxxx.individuals.error.ErrorCode;
import com.dezxxx.individuals.gateway.keycloak.client.KeycloakClient;
import com.dezxxx.individuals.logging.RequestLog;
import com.dezxxx.individuals.metrics.AuthMetrics;
import com.dezxxx.individuals.util.KeycloakClaims;
import com.dezxxx.individuals.util.Spans;
import io.micrometer.observation.ObservationRegistry;
import java.util.UUID;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Slf4j
@Service
@RequiredArgsConstructor
public class TokenService {

    private final KeycloakClient keycloakClient;
    private final AuthMetrics metrics;

    private final ReactiveJwtDecoder jwtDecoder;

    private final ObservationRegistry observationRegistry;

    public Mono<TokenResponseDto> login(String email, String password) {
        return keycloakClient.login(email, password)
                .transform(span("login.getToken"))
                .flatMap(tokens -> withUserUid(tokens).transform(span("login.readUserUid")))
                .doFirst(metrics::loginStarted)
                .doOnError(cause -> metrics.loginFailed())
                .doOnSuccess(tokens -> log.info("Logged in {}", tokens.getUserUid()))
                .transform(span("login"));
    }

    public Mono<TokenResponseDto> issueTokens(String email, String password) {
        return keycloakClient.login(email, password)
                .flatMap(this::withUserUid);
    }

    // invalid_grant here means a dead refresh token, not a wrong password: no password was sent
    public Mono<TokenResponseDto> refresh(String refreshToken) {
        return keycloakClient.refresh(refreshToken)
                .transform(span("refresh.getToken"))
                .flatMap(tokens -> withUserUid(tokens).transform(span("refresh.readUserUid")))
                .onErrorMap(TokenService::isRefusedCredentials,
                        ex -> new ApiException(ErrorCode.REFRESH_TOKEN_INVALID))
                .doFirst(metrics::refreshStarted)
                .transform(span("refresh"));
    }

    private <T> Function<Mono<T>, Mono<T>> span(String name) {
        return Spans.named(name, observationRegistry);
    }

    private static boolean isRefusedCredentials(Throwable cause) {
        return cause instanceof ApiException api && api.getErrorCode() == ErrorCode.INVALID_CREDENTIALS;
    }

    // user_uid is not in Keycloak's answer, only inside the access token
    private Mono<TokenResponseDto> withUserUid(TokenResponseDto tokens) {
        return jwtDecoder.decode(tokens.getAccessToken())
                .map(jwt -> {
                    UUID userUid = KeycloakClaims.requireUserUid(jwt);
                    RequestLog.userUid(userUid);
                    return tokens.userUid(userUid);
                })
                .onErrorMap(ex -> !(ex instanceof ApiException), ex -> {
                    log.error("Could not read the access token Keycloak just issued", ex);
                    return new ApiException(ErrorCode.INTERNAL_ERROR);
                });
    }
}

package com.dezxxx.individuals.service;

import com.dezxxx.individuals.api.model.TokenResponse;
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

    public Mono<TokenResponse> login(String email, String password) {
        return keycloakClient.login(email, password)
                .transform(span("login.getToken"))
                .flatMap(tokens -> withUserUid(tokens).transform(span("login.readUserUid")))
                .doFirst(metrics::loginStarted)
                .doOnError(cause -> metrics.loginFailed())
                .doOnSuccess(tokens -> log.info("Logged in {}", tokens.getUserUid()))
                .transform(span("login"));
    }

    // no login counters: the user did not log in, registration did
    public Mono<TokenResponse> issueTokens(String email, String password) {
        return keycloakClient.login(email, password)
                .flatMap(this::withUserUid);
    }

    // refresh token -> new pair. Keycloak answers invalid_grant both to a wrong
    // password and to a dead refresh token; here no password was sent, so the
    // refusal is renamed to REFRESH_TOKEN_INVALID - "session ended", not "wrong password"
    public Mono<TokenResponse> refresh(String refreshToken) {
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

    // Keycloak's token answer has no user_uid - it sits inside the access token,
    // so the token is decoded and the claim copied into the response
    private Mono<TokenResponse> withUserUid(TokenResponse tokens) {
        return jwtDecoder.decode(tokens.getAccessToken())
                .map(jwt -> {
                    UUID userUid = KeycloakClaims.requireUserUid(jwt);
                    RequestLog.userUid(userUid);
                    return tokens.userUid(userUid);
                })
                // our own fresh token failed our own decoder: keys rotated or
                // clocks disagree - not the caller's fault
                .onErrorMap(ex -> !(ex instanceof ApiException), ex -> {
                    log.error("Could not read the access token Keycloak just issued", ex);
                    return new ApiException(ErrorCode.INTERNAL_ERROR);
                });
    }
}

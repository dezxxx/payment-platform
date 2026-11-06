package com.dezxxx.individuals.unit.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dezxxx.individuals.error.ApiException;
import com.dezxxx.individuals.error.ErrorCode;
import com.dezxxx.individuals.util.CurrentUser;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@DisplayName("CurrentUser")
class CurrentUserTest {

    private static final UUID USER_UID = UUID.fromString("6f1d2a4e-8c3b-4a7f-9e2d-1b5c7a9f0e33");

    @Test
    @DisplayName("given an authenticated request, when reading the token, then the verified Jwt comes back")
    void readsJwt() {
        // given
        Jwt jwt = jwt(USER_UID.toString());
        ServerWebExchange exchange = mock(ServerWebExchange.class);
        when(exchange.getPrincipal()).thenReturn(Mono.just(new JwtAuthenticationToken(jwt)));

        // when / then
        StepVerifier.create(CurrentUser.jwt(exchange))
                .expectNext(jwt)
                .verifyComplete();
    }

    @Test
    @DisplayName("given no principal, when reading the token, then AUTHENTICATION_REQUIRED")
    void noPrincipal() {
        // given
        ServerWebExchange exchange = mock(ServerWebExchange.class);
        when(exchange.getPrincipal()).thenReturn(Mono.empty());

        // when / then
        StepVerifier.create(CurrentUser.jwt(exchange))
                .expectErrorSatisfies(error ->
                        assertThat(((ApiException) error).getErrorCode()).isEqualTo(ErrorCode.AUTHENTICATION_REQUIRED))
                .verify();
    }

    @Test
    @DisplayName("given a token with user_uid, when reading the user, then that user_uid")
    void readsUserUid() {
        // when / then
        StepVerifier.create(CurrentUser.userUid(jwt(USER_UID.toString())))
                .expectNext(USER_UID)
                .verifyComplete();
    }

    @Test
    @DisplayName("given a token without a usable user_uid, when reading the user, then INTERNAL_ERROR - the realm mapper is broken")
    void userUidMissing() {
        // when / then
        StepVerifier.create(CurrentUser.userUid(jwt("not-a-uuid")))
                .expectErrorSatisfies(error ->
                        assertThat(((ApiException) error).getErrorCode()).isEqualTo(ErrorCode.INTERNAL_ERROR))
                .verify();
    }

    private static Jwt jwt(String userUid) {
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("0b7a5f2c-19d4-4a8e-9c3b-77e1a0d4f5b6")
                .claim("user_uid", userUid)
                .issuedAt(Instant.parse("2026-03-14T09:30:00Z"))
                .expiresAt(Instant.parse("2026-03-14T09:35:00Z"))
                .build();
    }
}
